package ai.xdev.aisdlc.scm.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import ai.xdev.aisdlc.scm.outbound.ScmFeedbackPublisher.PolicyFeedback;
import ai.xdev.aisdlc.scm.outbound.ScmFeedbackPublisher.ScmFeedbackException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbound contract, exercised against a real HTTP server rather than a mocked client.
 *
 * <p>Four of these publishers build a URL, choose a provider state vocabulary, and encode a body. Mocking the HTTP
 * layer would assert that the code calls itself the way it was written; an embedded server checks the request that
 * would actually leave the process — the path, the method, the auth scheme, and the state string a provider would
 * receive.
 *
 * <p>No provider tenant has been contacted. What this pins is the request shape and the mapping decisions, not that
 * GitLab or Jira accepts them.
 */
class ScmFeedbackContractTest {
  private record Captured(String method, String path, String authorization, JsonNode body) {}

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<Captured> captured = new ArrayList<>();
  private final AtomicInteger status = new AtomicInteger(201);
  private HttpServer server;
  private String baseUrl;

  @BeforeEach void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] request = exchange.getRequestBody().readAllBytes();
      captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          request.length == 0 ? mapper.nullNode() : mapper.readTree(request)));
      byte[] response = "{\"id\":4242,\"key\":\"AI_SDLC_POLICY\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach void stopServer() { server.stop(0); }

  @Test void everyPublisherRefusesUntilItIsConfigured() {
    ScmConnectorProperties blank = new ScmConnectorProperties();
    ScmFeedbackSupport support = new ScmFeedbackSupport(mapper);
    for (ScmFeedbackPublisher publisher : List.of(
        new GitLabFeedbackPublisher(blank, support), new BitbucketFeedbackPublisher(blank, support),
        new AzureDevOpsFeedbackPublisher(blank, support), new JiraFeedbackPublisher(blank, support))) {
      assertFalse(publisher.isConfigured(), publisher.provider() + " must not publish without an API token");
    }
  }

  @Test void outboundConfigurationIsSeparateFromTheInboundWebhookSecret() {
    // Ingesting from a provider must not imply permission to write statuses back to it.
    ScmConnectorProperties properties = new ScmConnectorProperties();
    properties.forKey("gitlab").setSecret("inbound-webhook-secret");

    assertTrue(properties.forKey("gitlab").isConfigured());
    assertFalse(new GitLabFeedbackPublisher(properties, new ScmFeedbackSupport(mapper)).isConfigured());
  }

  @Test void actionRequiredNeverDegradesIntoAPassingStateOnAnyProvider() {
    // The property that matters: no provider's vocabulary has an exact equivalent of action_required, and every
    // approximation must land on the blocking side. A mapping bug here silently approves ungoverned changes.
    publishAll(ScmPolicyConclusion.ACTION_REQUIRED);

    assertEquals("failed", captured.get(0).body().path("state").asText(), "GitLab");
    assertEquals("FAILED", captured.get(1).body().path("state").asText(), "Bitbucket");
    assertEquals("failed", captured.get(2).body().path("state").asText(), "Azure DevOps");
    assertTrue(captured.get(3).body().toString().contains("blocked and requires action"), "Jira");
  }

  @Test void aPassingDecisionMapsToEachProvidersSuccessState() {
    publishAll(ScmPolicyConclusion.SUCCESS);

    assertEquals("success", captured.get(0).body().path("state").asText());
    assertEquals("SUCCESSFUL", captured.get(1).body().path("state").asText());
    assertEquals("succeeded", captured.get(2).body().path("state").asText());
  }

  @Test void eachProviderIsAddressedTheWayItsApiRequires() {
    publishAll(ScmPolicyConclusion.FAILURE);

    // GitLab addresses a project by URL-encoded group/project, so the slash must be escaped, not passed through.
    assertEquals("/api/v4/projects/acme%2Fplatform/statuses/abc123", captured.get(0).path());
    assertEquals("/2.0/repositories/acme/platform/commit/abc123/statuses/build", captured.get(1).path());
    // Azure DevOps attaches the status to the pull request, not the commit, and needs the configured organization.
    assertEquals("/contoso/acme/_apis/git/repositories/platform/pullRequests/7/statuses?api-version=7.1", captured.get(2).path());
    assertEquals("/rest/api/3/issue/AISDLC-9/comment", captured.get(3).path());

    assertTrue(captured.get(0).authorization().startsWith("Bearer "), "GitLab uses a bearer token");
    assertTrue(captured.get(2).authorization().startsWith("Basic "), "Azure DevOps PATs are the password half of Basic");
    assertTrue(captured.get(3).authorization().startsWith("Basic "), "Jira authenticates with user and API token");
  }

  @Test void anEventThatTheProviderCannotBeAddressedByIsSkippedRatherThanGuessed() {
    ScmFeedbackSupport support = new ScmFeedbackSupport(mapper);
    ScmRepositoryLink link = link(ScmProvider.GITLAB, "acme/platform");
    // No commit sha, no pull request number, no issue key.
    ScmEvent bare = event(ScmProvider.GITLAB, "acme/platform", null, null, null);

    assertTrue(new GitLabFeedbackPublisher(configured("gitlab"), support).publish(link, bare, feedback(ScmPolicyConclusion.FAILURE)).isEmpty());
    assertTrue(new BitbucketFeedbackPublisher(configured("bitbucket"), support).publish(link, bare, feedback(ScmPolicyConclusion.FAILURE)).isEmpty());
    assertTrue(new AzureDevOpsFeedbackPublisher(azure(), support).publish(link, bare, feedback(ScmPolicyConclusion.FAILURE)).isEmpty());
    assertTrue(new JiraFeedbackPublisher(jira(), support).publish(link, bare, feedback(ScmPolicyConclusion.FAILURE)).isEmpty());
    assertTrue(captured.isEmpty(), "a skip must not send a request");
  }

  @Test void aRejectedPublishFailsWithTheStatusCodeAndNoProviderResponseBody() {
    status.set(422);
    ScmFeedbackException error = assertThrows(ScmFeedbackException.class, () -> publishAll(ScmPolicyConclusion.FAILURE));

    assertTrue(error.getMessage().contains("422"));
    // The provider echoes the submitted request, which carries the Authorization header and the repository path.
    assertFalse(error.getMessage().contains("AI_SDLC_POLICY"), "the provider response body must not reach the message");
  }

  @Test void aSingleComponentRepositoryIsRejectedRatherThanWrittenToTheWrongPlace() {
    ScmFeedbackSupport support = new ScmFeedbackSupport(mapper);
    ScmRepositoryLink malformed = link(ScmProvider.BITBUCKET, "platform");
    ScmEvent pushed = event(ScmProvider.BITBUCKET, "platform", "abc123", null, null);

    assertThrows(ScmFeedbackException.class, () ->
        new BitbucketFeedbackPublisher(configured("bitbucket"), support).publish(malformed, pushed, feedback(ScmPolicyConclusion.SUCCESS)));
    assertTrue(captured.isEmpty());
  }

  private void publishAll(ScmPolicyConclusion conclusion) {
    ScmFeedbackSupport support = new ScmFeedbackSupport(mapper);
    new GitLabFeedbackPublisher(configured("gitlab"), support)
        .publish(link(ScmProvider.GITLAB, "acme/platform"), event(ScmProvider.GITLAB, "acme/platform", "abc123", 7, null), feedback(conclusion));
    new BitbucketFeedbackPublisher(configured("bitbucket"), support)
        .publish(link(ScmProvider.BITBUCKET, "acme/platform"), event(ScmProvider.BITBUCKET, "acme/platform", "abc123", 7, null), feedback(conclusion));
    new AzureDevOpsFeedbackPublisher(azure(), support)
        .publish(link(ScmProvider.AZURE_DEVOPS, "acme/platform"), event(ScmProvider.AZURE_DEVOPS, "acme/platform", "abc123", 7, null), feedback(conclusion));
    new JiraFeedbackPublisher(jira(), support)
        .publish(link(ScmProvider.JIRA, "AISDLC"), event(ScmProvider.JIRA, "AISDLC", null, null, "AISDLC-9"), feedback(conclusion));
  }

  private ScmConnectorProperties configured(String key) {
    ScmConnectorProperties properties = new ScmConnectorProperties();
    properties.forKey(key).setApiBaseUrl(baseUrl);
    properties.forKey(key).setApiToken("outbound-token");
    return properties;
  }

  private ScmConnectorProperties azure() {
    ScmConnectorProperties properties = configured("azure-devops");
    properties.forKey("azure-devops").setOrganization("contoso");
    return properties;
  }

  private ScmConnectorProperties jira() {
    ScmConnectorProperties properties = configured("jira");
    properties.forKey("jira").setApiUser("automation@example.com");
    return properties;
  }

  private static PolicyFeedback feedback(ScmPolicyConclusion conclusion) {
    return new PolicyFeedback(conclusion, "ai-sdlc/policy", "Validation evidence is required.", "event-1", null);
  }

  private static ScmRepositoryLink link(ScmProvider provider, String fullName) {
    return new ScmRepositoryLink(UUID.randomUUID(), provider, fullName, null, "main", true, "tester");
  }

  private static ScmEvent event(ScmProvider provider, String fullName, String commitSha, Integer pullRequest, String externalKey) {
    ScmEvent event = new ScmEvent(UUID.randomUUID(), UUID.randomUUID(), provider, "delivery-1", ScmEventType.PULL_REQUEST,
        "opened", fullName, null, "refs/heads/topic", commitSha, pullRequest, null, null, "sha", "{}");
    event.assignExternalKey(externalKey);
    return event;
  }
}
