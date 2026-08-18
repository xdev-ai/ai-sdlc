package ai.xdev.aisdlc.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The single bundle of standards an AI coding agent must work under, assembled by the server.
 *
 * <p>Why the server and not each client. An agent on a developer's machine needs to know the active constitution, the
 * active policies, which Spec Kit versions are pinned, and where the project's documentation lives. Every one of those
 * is already available as a separate endpoint, so a client could fetch four things and combine them — and then two
 * machines with slightly different client versions would disagree about what the rules are. Rules that differ per
 * machine are not rules. This endpoint is the authority, and clients render what it says.
 *
 * <p>Resolution is delegated to {@link GovernanceCatalogService} rather than reimplemented here. Policies and
 * constitutions resolve organization-level records plus project overrides, and membership is checked as part of that
 * resolution; a second copy of those queries would eventually drift from the first.
 *
 * <p>{@code invariants} are statements about how this platform behaves, not invented policy. They are here because an
 * agent that does not know them produces confidently wrong work: it will look for a UI button that creates validation
 * evidence, or treat an empty documentation search as proof that a subject is undocumented.
 */
@Service
public class AgentRulesService {
  /**
   * Facts about the platform that change what a correct agent does. Each one is enforced somewhere in this codebase,
   * not aspirational.
   */
  private static final List<String> INVARIANTS = List.of(
      "Validation is deterministic and never calls a model. The model pin is recorded as provenance only, so a "
          + "validation result must never be produced or edited by an agent.",
      "Validation evidence enters the platform through the CLI or an SCM webhook. There is no UI action that creates a "
          + "validation run, so do not instruct a human to look for one.",
      "Spec Kits are immutable once registered, and only pinned kits apply to a project. Nothing is assumed by "
          + "default: if no kit is pinned, the correct action is to pin one, not to guess a version.",
      "A finding may be closed as FALSE_POSITIVE or ACCEPTED_RISK only with a written rationale, because choosing not "
          + "to remediate has to stay auditable.",
      "Documentation retrieval is lexical, not semantic: there is no embedding index. An empty search result means no "
          + "wording matched, never that the documentation does not cover the subject.",
      "Traceability links are explicit governed assertions. The platform never infers a missing link, and neither "
          + "should an agent.");

  private final JdbcTemplate jdbc;
  private final GovernanceCatalogService governance;

  public AgentRulesService(JdbcTemplate jdbc, GovernanceCatalogService governance) {
    this.jdbc = jdbc;
    this.governance = governance;
  }

  public record ConstitutionView(String version, String content, Instant activatedAt) {}

  public record PolicyView(String key, String version, String rule, boolean projectScoped) {}

  public record PinnedKitView(String slug, String version, String layer, int precedence, String lifecycleStatus) {}

  public record KnowledgeSpaceView(String spaceKey, String name, long pageCount) {}

  /**
   * @param completeness how much of the governing set actually exists, so a client can tell "no rules apply" from
   *     "nobody has configured the rules yet" — two situations an agent must not confuse
   */
  public record AgentRulesView(UUID projectId, String projectName, UUID organizationId, String organizationSlug,
      Instant generatedAt, ConstitutionView constitution, List<PolicyView> policies, List<PinnedKitView> pinnedKits,
      List<KnowledgeSpaceView> knowledgeSpaces, List<String> invariants, String completeness,
      List<String> missing) {}

  public AgentRulesView rulesFor(UUID projectId, String actor) {
    // Delegated, so membership is enforced by the same code path the portal uses.
    List<Map<String, Object>> constitutions = governance.listConstitutions(projectId, actor);
    List<Map<String, Object>> policies = governance.listPolicies(projectId, actor);
    List<Map<String, Object>> kits = governance.projectKits(projectId, actor);

    Map<String, Object> project = jdbc.queryForMap("""
        select p.name as project_name, o.slug as organization_slug, p.organization_id
        from projects p join organizations o on o.id = p.organization_id where p.id = ?
        """, projectId);
    UUID organizationId = (UUID) project.get("organization_id");

    List<KnowledgeSpaceView> spaces = jdbc.query("""
        select s.space_key, s.name, (select count(*) from knowledge_pages p where p.space_id = s.id) as page_count
        from knowledge_spaces s
        where s.organization_id = ? and s.archived_at is null and (s.project_id is null or s.project_id = ?)
        order by s.space_key
        """, (rs, index) -> new KnowledgeSpaceView(rs.getString("space_key"), rs.getString("name"), rs.getLong("page_count")),
        organizationId, projectId);

    // The most recently activated constitution wins. Several may be active at organization and project level, and an
    // agent given two sets of principles has no way to choose between them.
    ConstitutionView constitution = constitutions.stream()
        .max(java.util.Comparator.comparing(row -> String.valueOf(row.get("activated_at")),
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
        .map(row -> new ConstitutionView(String.valueOf(row.get("version")), String.valueOf(row.get("content")),
            row.get("activated_at") instanceof java.sql.Timestamp stamp ? stamp.toInstant() : null))
        .orElse(null);

    List<PolicyView> policyViews = policies.stream()
        .map(row -> new PolicyView(String.valueOf(row.get("key")), String.valueOf(row.get("version")),
            String.valueOf(row.get("rule")), row.get("project_id") != null))
        .toList();

    List<PinnedKitView> kitViews = kits.stream()
        .map(row -> new PinnedKitView(String.valueOf(row.get("slug")), String.valueOf(row.get("version")),
            String.valueOf(row.get("layer")),
            row.get("precedence") instanceof Number number ? number.intValue() : 0,
            String.valueOf(row.get("lifecycle_status"))))
        .toList();

    List<String> missing = new java.util.ArrayList<>();
    if (constitution == null) missing.add("no active constitution");
    if (policyViews.isEmpty()) missing.add("no active policy");
    if (kitViews.isEmpty()) missing.add("no pinned Spec Kit");
    if (spaces.isEmpty()) missing.add("no documentation space");
    String completeness = missing.isEmpty() ? "COMPLETE" : missing.size() == 4 ? "UNCONFIGURED" : "PARTIAL";

    // organizationId travels in the response so a client that knows only a project can reach the documentation
    // endpoints, which are organization-scoped. Without it every client would need a second configuration value.
    return new AgentRulesView(projectId, String.valueOf(project.get("project_name")), organizationId,
        String.valueOf(project.get("organization_slug")), Instant.now(), constitution, policyViews, kitViews, spaces,
        INVARIANTS, completeness, List.copyOf(missing));
  }

  /**
   * The same bundle as text, for an agent that is handed a system prompt rather than JSON.
   *
   * <p>Assembled here so every client produces identical wording. A client that formats the rules itself is a client
   * that can quietly soften them.
   */
  public String rulesAsMarkdown(UUID projectId, String actor) {
    AgentRulesView rules = rulesFor(projectId, actor);
    StringBuilder text = new StringBuilder();
    text.append("# Governing rules for ").append(rules.projectName())
        .append(" (").append(rules.organizationSlug()).append(")\n\n");
    text.append("Configuration state: ").append(rules.completeness());
    if (!rules.missing().isEmpty()) text.append(" — ").append(String.join(", ", rules.missing()));
    text.append("\n\n## Platform invariants\n\n");
    rules.invariants().forEach(invariant -> text.append("- ").append(invariant).append('\n'));

    text.append("\n## Constitution\n\n");
    if (rules.constitution() == null) {
      text.append("No active constitution. Do not invent one: ask for it to be published and activated.\n");
    } else {
      text.append("Version ").append(rules.constitution().version()).append("\n\n")
          .append(rules.constitution().content()).append('\n');
    }

    text.append("\n## Active policies\n\n");
    if (rules.policies().isEmpty()) {
      text.append("No active policy.\n\n");
    } else {
      for (PolicyView policy : rules.policies()) {
        text.append("### ").append(policy.key()).append(" (").append(policy.version()).append(")")
            .append(policy.projectScoped() ? " — project override\n\n" : "\n\n")
            .append("```\n").append(policy.rule()).append("\n```\n\n");
      }
    }

    text.append("## Pinned Spec Kits\n\n");
    if (rules.pinnedKits().isEmpty()) {
      text.append("None pinned. Validation will not assume a default version.\n");
    } else {
      rules.pinnedKits().forEach(kit -> text.append("- ").append(kit.slug()).append(' ').append(kit.version())
          .append(" · ").append(kit.layer()).append(" · precedence ").append(kit.precedence()).append('\n'));
    }

    text.append("\n## Documentation available for retrieval\n\n");
    if (rules.knowledgeSpaces().isEmpty()) {
      text.append("No documentation space. Retrieval will return nothing; do not read that as an absence of "
          + "requirements.\n");
    } else {
      rules.knowledgeSpaces().forEach(space -> text.append("- ").append(space.spaceKey()).append(" — ")
          .append(space.name()).append(" (").append(space.pageCount()).append(" pages)\n"));
    }
    return text.toString();
  }

  /** Guards against a caller passing a project that does not exist, so the error names the cause. */
  public void requireProject(UUID projectId) {
    Integer found = jdbc.queryForObject("select count(*) from projects where id = ?", Integer.class, projectId);
    if (!Objects.equals(found, 1)) throw new IllegalArgumentException("Project not found");
  }
}
