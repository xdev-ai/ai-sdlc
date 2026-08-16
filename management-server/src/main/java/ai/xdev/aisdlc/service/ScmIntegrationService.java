package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.domain.Project;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import ai.xdev.aisdlc.domain.ValidationRun;
import ai.xdev.aisdlc.repo.Repositories.ScmEventRepository;
import ai.xdev.aisdlc.repo.Repositories.ScmRepositoryLinkRepository;
import ai.xdev.aisdlc.repo.Repositories.ValidationRunRepository;
import ai.xdev.aisdlc.web.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScmIntegrationService {
  public record WebhookIngestResult(UUID eventId, boolean duplicate, boolean accepted, String disposition) {}
  private final ProjectAccessService access;
  private final ScmRepositoryLinkRepository repositoryLinks;
  private final ScmEventRepository events;
  private final ValidationRunRepository validations;
  private final AuditService audit;
  private final GitHubPolicyGateService policyGate;
  private final ObjectMapper objectMapper;
  private final org.springframework.beans.factory.ObjectProvider<ChaosFaultRegistry> chaosFaults;
  private ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry = ai.xdev.aisdlc.telemetry.GovernanceTelemetry.inert();
  @org.springframework.beans.factory.annotation.Autowired public void setTelemetry(ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry) { this.telemetry = telemetry; }

  public ScmIntegrationService(ProjectAccessService access, ScmRepositoryLinkRepository repositoryLinks, ScmEventRepository events, ValidationRunRepository validations, AuditService audit, GitHubPolicyGateService policyGate, ObjectMapper objectMapper) {
    this(access, repositoryLinks, events, validations, audit, policyGate, objectMapper, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScmIntegrationService(ProjectAccessService access, ScmRepositoryLinkRepository repositoryLinks, ScmEventRepository events, ValidationRunRepository validations, AuditService audit, GitHubPolicyGateService policyGate, ObjectMapper objectMapper, org.springframework.beans.factory.ObjectProvider<ChaosFaultRegistry> chaosFaults) {
    this.chaosFaults = chaosFaults;
    this.access = access;
    this.repositoryLinks = repositoryLinks;
    this.events = events;
    this.validations = validations;
    this.audit = audit;
    this.policyGate = policyGate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public UUID linkRepository(UUID projectId, String actor, ScmProvider provider, String repositoryFullName, Long installationId, String defaultBranch, boolean policyGateEnabled) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    String normalizedName = normalizeRepository(repositoryFullName);
    repositoryLinks.findByProviderAndRepositoryFullName(provider, normalizedName).ifPresent(existing -> {
      throw new IllegalStateException("Repository is already linked to project " + existing.getProjectId());
    });
    ScmRepositoryLink link = repositoryLinks.save(new ScmRepositoryLink(projectId, provider, normalizedName, installationId, blankToNull(defaultBranch), policyGateEnabled, actor));
    audit.append(project.getOrganizationId(), projectId, actor, "SCM_REPOSITORY_LINKED", "scm_repository_link", link.getId().toString(), "{\"provider\":\"" + provider + "\",\"repository\":\"" + normalizedName + "\"}");
    return link.getId();
  }

  @Transactional
  public WebhookIngestResult ingestGitHub(String deliveryId, String eventName, byte[] rawPayload) {
    return telemetry.recordUnchecked("aisdlc.scm.ingest", "scm-ingestion-freshness", () -> ingestGitHubDelivery(deliveryId, eventName, rawPayload));
  }

  private WebhookIngestResult ingestGitHubDelivery(String deliveryId, String eventName, byte[] rawPayload) {
    if (deliveryId == null || deliveryId.isBlank()) throw new IllegalArgumentException("X-GitHub-Delivery is required");
    ScmEventType eventType = githubEventType(eventName);
    Optional<ScmEvent> prior = events.findByProviderAndDeliveryId(ScmProvider.GITHUB, deliveryId);
    if (prior.isPresent()) return new WebhookIngestResult(prior.get().getId(), true, true, "duplicate");
    try {
      JsonNode payload = objectMapper.readTree(rawPayload);
      String fullName = payload.path("repository").path("full_name").asText();
      ScmRepositoryLink link = repositoryLinks.findByProviderAndRepositoryFullName(ScmProvider.GITHUB, fullName).orElse(null);
      if (link == null) return new WebhookIngestResult(null, false, false, "repository_not_linked");
      // Interrupt before the idempotency marker commits: the sender retries and the duplicate check makes it safe.
      if (chaosFaults != null) chaosFaults.ifAvailable(registry -> registry.check(ChaosFaultRegistry.Component.SCM_INGRESS));
      ScmEvent event = events.save(new ScmEvent(link.getProjectId(), link.getId(), ScmProvider.GITHUB, deliveryId, eventType, nullableText(payload, "action"), fullName,
          nullableLong(payload.path("installation").path("id")), extractRef(payload, eventType), extractCommitSha(payload, eventType), extractPullRequestNumber(payload),
          nullableLong(payload.path("workflow_run").path("id")), nullableText(payload.path("release"), "tag_name"), sha256(rawPayload), new String(rawPayload, StandardCharsets.UTF_8)));
      if (eventType == ScmEventType.PULL_REQUEST && isPolicyGateAction(event.getAction())) {
        policyGate.publishRequiredEvidenceGate(link, event).ifPresent(event::recordPolicyCheckRun);
      }
      event.markProcessed();
      Project project = access.requireProject(link.getProjectId());
      audit.append(project.getOrganizationId(), project.getId(), "github-app", "SCM_EVENT_INGESTED", "scm_event", event.getId().toString(), "{\"eventType\":\"" + eventType + "\",\"deliveryId\":\"" + deliveryId + "\",\"payloadSha256\":\"" + event.getPayloadSha256() + "\"}");
      return new WebhookIngestResult(event.getId(), false, true, "processed");
    } catch (RuntimeException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException("GitHub webhook payload is not valid JSON", error);
    }
  }

  @Transactional
  public void linkValidationRun(UUID projectId, UUID eventId, UUID validationRunId, String actor) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    ScmEvent event = events.findByIdAndProjectId(eventId, projectId).orElseThrow(() -> new IllegalArgumentException("SCM event not found"));
    ValidationRun validation = validations.findById(validationRunId).orElseThrow(() -> new IllegalArgumentException("Validation run not found"));
    if (!validation.getProjectId().equals(projectId)) throw new IllegalArgumentException("Validation run belongs to another project");
    event.linkValidationRun(validationRunId);
    ScmRepositoryLink link = repositoryLinks.findById(event.getRepositoryLinkId()).orElseThrow(() -> new IllegalStateException("SCM repository link is missing"));
    policyGate.publishValidationDecision(link, event, validation);
    audit.append(project.getOrganizationId(), projectId, actor, "SCM_VALIDATION_LINKED", "scm_event", event.getId().toString(), "{\"validationRunId\":\"" + validationRunId + "\"}");
  }

  @Transactional(readOnly = true)
  public PageResponse<Map<String, Object>> listEvents(UUID projectId, String actor, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    int boundedPage = Math.max(page, 0);
    int boundedSize = Math.min(Math.max(size, 1), 100);
    return PageResponse.from(events.findByProjectId(projectId, PageRequest.of(boundedPage, boundedSize)).map(this::eventView));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listRepositoryLinks(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return repositoryLinks.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::repositoryView).toList();
  }

  private Map<String, Object> eventView(ScmEvent event) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", event.getId()); view.put("provider", event.getProvider()); view.put("eventType", event.getEventType()); view.put("action", event.getAction());
    view.put("repository", event.getRepositoryFullName()); view.put("commitSha", event.getCommitSha()); view.put("pullRequestNumber", event.getPullRequestNumber());
    view.put("workflowRunId", event.getWorkflowRunId()); view.put("releaseTag", event.getReleaseTag()); view.put("validationRunId", event.getValidationRunId());
    view.put("policyCheckRunId", event.getPolicyCheckRunId()); view.put("payloadSha256", event.getPayloadSha256()); view.put("processingStatus", event.getProcessingStatus());
    view.put("failureReason", event.getFailureReason()); view.put("receivedAt", event.getReceivedAt()); view.put("processedAt", event.getProcessedAt());
    return view;
  }

  private Map<String, Object> repositoryView(ScmRepositoryLink link) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", link.getId()); view.put("provider", link.getProvider()); view.put("repository", link.getRepositoryFullName()); view.put("installationId", link.getInstallationId());
    view.put("defaultBranch", link.getDefaultBranch()); view.put("policyGateEnabled", link.isPolicyGateEnabled()); view.put("createdBy", link.getCreatedBy()); view.put("createdAt", link.getCreatedAt());
    return view;
  }

  private ScmEventType githubEventType(String eventName) {
    return switch (eventName == null ? "" : eventName) {
      case "push" -> ScmEventType.PUSH;
      case "pull_request" -> ScmEventType.PULL_REQUEST;
      case "check_run" -> ScmEventType.CHECK_RUN;
      case "workflow_run" -> ScmEventType.WORKFLOW_RUN;
      case "release" -> ScmEventType.RELEASE;
      default -> throw new IllegalArgumentException("Unsupported GitHub webhook event");
    };
  }
  private String extractRef(JsonNode payload, ScmEventType type) {
    return switch (type) {
      case PUSH -> nullableText(payload, "ref");
      case PULL_REQUEST -> nullableText(payload.path("pull_request").path("head"), "ref");
      case CHECK_RUN -> nullableText(payload.path("check_run"), "head_branch");
      case WORKFLOW_RUN -> nullableText(payload.path("workflow_run"), "head_branch");
      case RELEASE -> nullableText(payload.path("release"), "target_commitish");
    };
  }
  private String extractCommitSha(JsonNode payload, ScmEventType type) {
    return switch (type) {
      case PUSH -> nullableText(payload, "after");
      case PULL_REQUEST -> nullableText(payload.path("pull_request").path("head"), "sha");
      case CHECK_RUN -> nullableText(payload.path("check_run"), "head_sha");
      case WORKFLOW_RUN -> nullableText(payload.path("workflow_run"), "head_sha");
      case RELEASE -> nullableText(payload.path("release"), "target_commitish");
    };
  }
  private Integer extractPullRequestNumber(JsonNode payload) { return payload.hasNonNull("number") ? payload.path("number").asInt() : null; }
  private Long nullableLong(JsonNode value) { return value == null || value.isMissingNode() || value.isNull() ? null : value.asLong(); }
  private String nullableText(JsonNode node, String field) { return node == null || node.path(field).isMissingNode() || node.path(field).isNull() ? null : blankToNull(node.path(field).asText()); }
  private boolean isPolicyGateAction(String action) { return Set.of("opened", "reopened", "synchronize", "ready_for_review").contains(action); }
  private String normalizeRepository(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Repository must be in owner/name form");
    return value;
  }
  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
  private String sha256(byte[] payload) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
    catch (Exception error) { throw new IllegalStateException("Unable to hash SCM event payload", error); }
  }
}
