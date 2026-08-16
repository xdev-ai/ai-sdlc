package ai.xdev.aisdlc.scm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The contract every connector must hold, exercised against all four implementations.
 *
 * <p>These are the properties that make a connector safe to add: it refuses everything until configured, it refuses
 * an unverified request, it produces a stable delivery identifier so shared idempotency works, and it declines a
 * payload it does not represent instead of guessing.
 */
class ScmConnectorContractTest {
  private static final String SECRET = "connector-shared-secret";
  private final ObjectMapper json = new ObjectMapper();

  private static ScmConnectorProperties properties(String key, String secret) {
    ScmConnectorProperties properties = new ScmConnectorProperties();
    properties.forKey(key).setSecret(secret);
    return properties;
  }

  private static String hmac(byte[] payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
  }

  private List<ScmConnector> configuredConnectors() {
    return List.of(
        new GitLabConnector(properties("gitlab", SECRET)),
        new BitbucketConnector(properties("bitbucket", SECRET)),
        new AzureDevOpsConnector(properties("azure-devops", SECRET)),
        new JiraConnector(properties("jira", SECRET)));
  }

  private List<ScmConnector> unconfiguredConnectors() {
    return List.of(
        new GitLabConnector(new ScmConnectorProperties()),
        new BitbucketConnector(new ScmConnectorProperties()),
        new AzureDevOpsConnector(new ScmConnectorProperties()),
        new JiraConnector(new ScmConnectorProperties()));
  }

  @Test
  void everyConnectorRefusesUntilItIsConfigured() {
    for (ScmConnector connector : unconfiguredConnectors()) {
      assertFalse(connector.isConfigured(), connector.provider() + " reports configured with no secret");
      assertFalse(connector.verify("{}".getBytes(StandardCharsets.UTF_8), Map.of()),
          connector.provider() + " verified a request with no configured secret");
    }
  }

  @Test
  void everyConnectorRefusesAMissingOrWrongCredential() throws Exception {
    byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
    for (ScmConnector connector : configuredConnectors()) {
      assertFalse(connector.verify(payload, Map.of()), connector.provider() + " accepted a request with no credential");
      assertFalse(connector.verify(payload, Map.of("x-gitlab-token", "wrong", "x-hub-signature", "sha256=deadbeef",
          "authorization", "Basic wrong")), connector.provider() + " accepted a wrong credential");
    }
  }

  @Test
  void everyProviderIsDistinctAndCoversTheNewConnectors() {
    var providers = configuredConnectors().stream().map(ScmConnector::provider).toList();
    assertEquals(providers.size(), providers.stream().distinct().count(), "two connectors claim the same provider");
    assertTrue(providers.containsAll(List.of(ScmProvider.GITLAB, ScmProvider.BITBUCKET, ScmProvider.AZURE_DEVOPS, ScmProvider.JIRA)));
  }

  @Test
  void gitlabVerifiesItsTokenAndMapsAMergeRequest() throws Exception {
    GitLabConnector connector = new GitLabConnector(properties("gitlab", SECRET));
    byte[] payload = ("{\"project\":{\"path_with_namespace\":\"group/app\"},"
        + "\"object_attributes\":{\"iid\":42,\"action\":\"open\",\"source_branch\":\"feature\",\"last_commit\":{\"id\":\"abc123\"}}}")
        .getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = Map.of("x-gitlab-token", SECRET, "x-gitlab-event", "Merge Request Hook", "x-gitlab-event-uuid", "uuid-1");

    assertTrue(connector.verify(payload, headers));
    var event = connector.parse(json.readTree(payload), headers).orElseThrow();
    assertEquals(ScmEventType.PULL_REQUEST, event.eventType());
    assertEquals("group/app", event.repositoryFullName());
    assertEquals(42, event.pullRequestNumber());
    assertEquals("uuid-1", event.deliveryId());
    assertEquals("abc123", event.commitSha());
  }

  @Test
  void bitbucketVerifiesAnHmacAndMapsAPullRequest() throws Exception {
    BitbucketConnector connector = new BitbucketConnector(properties("bitbucket", SECRET));
    byte[] payload = ("{\"repository\":{\"full_name\":\"team/app\"},"
        + "\"pullrequest\":{\"id\":7,\"source\":{\"branch\":{\"name\":\"topic\"},\"commit\":{\"hash\":\"def456\"}}}}")
        .getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = Map.of("x-hub-signature", hmac(payload), "x-event-key", "pullrequest:created", "x-request-uuid", "req-1");

    assertTrue(connector.verify(payload, headers));
    assertFalse(connector.verify("{\"tampered\":true}".getBytes(StandardCharsets.UTF_8), headers),
        "the signature must bind to the payload");
    var event = connector.parse(json.readTree(payload), headers).orElseThrow();
    assertEquals(ScmEventType.PULL_REQUEST, event.eventType());
    assertEquals("team/app", event.repositoryFullName());
    assertEquals(7, event.pullRequestNumber());
  }

  @Test
  void azureDevOpsVerifiesBasicAuthAndMapsAPullRequest() throws Exception {
    AzureDevOpsConnector connector = new AzureDevOpsConnector(properties("azure-devops", SECRET));
    byte[] payload = ("{\"id\":\"evt-1\",\"eventType\":\"git.pullrequest.created\",\"resource\":{\"pullRequestId\":11,"
        + "\"sourceRefName\":\"refs/heads/topic\",\"repository\":{\"name\":\"app\",\"project\":{\"name\":\"org\"}}}}")
        .getBytes(StandardCharsets.UTF_8);
    String basic = "Basic " + java.util.Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));

    assertTrue(connector.verify(payload, Map.of("authorization", basic)));
    assertFalse(connector.verify(payload, Map.of("authorization", "Bearer " + SECRET)), "only Basic is accepted");
    var event = connector.parse(json.readTree(payload), Map.of()).orElseThrow();
    assertEquals(ScmEventType.PULL_REQUEST, event.eventType());
    assertEquals("org/app", event.repositoryFullName());
    assertEquals(11, event.pullRequestNumber());
    assertEquals("evt-1", event.deliveryId());
  }

  @Test
  void jiraVerifiesAnHmacAndMapsAnIssueToItsProjectKey() throws Exception {
    JiraConnector connector = new JiraConnector(properties("jira", SECRET));
    byte[] payload = ("{\"id\":9001,\"webhookEvent\":\"jira:issue_updated\","
        + "\"issue\":{\"key\":\"GOV-12\",\"fields\":{\"project\":{\"key\":\"GOV\"}}}}").getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = Map.of("x-hub-signature", hmac(payload));

    assertTrue(connector.verify(payload, headers));
    var event = connector.parse(json.readTree(payload), headers).orElseThrow();
    assertEquals(ScmEventType.WORK_ITEM, event.eventType());
    assertEquals("GOV", event.repositoryFullName());
    assertEquals("GOV-12", event.externalKey());
    assertEquals("9001", event.deliveryId());
  }

  @Test
  void anUnrepresentedPayloadIsDeclinedRatherThanGuessed() throws Exception {
    JsonNode empty = json.readTree("{}");
    for (ScmConnector connector : configuredConnectors()) {
      assertTrue(connector.parse(empty, Map.of()).isEmpty(),
          connector.provider() + " invented an event from an unrecognised payload");
    }
  }

  @Test
  void aProviderWithoutADeliveryIdStillProducesAStableIdempotencyKey() throws Exception {
    // Azure DevOps and Jira can omit their identifier. Two replays of the same bytes must collide on one key, or
    // shared idempotency silently stops protecting against duplicate delivery.
    AzureDevOpsConnector connector = new AzureDevOpsConnector(properties("azure-devops", SECRET));
    String body = "{\"eventType\":\"git.push\",\"resource\":{\"repository\":{\"name\":\"app\",\"project\":{\"name\":\"org\"}}}}";
    JsonNode payload = json.readTree(body);
    String first = connector.parse(payload, Map.of()).orElseThrow().deliveryId();
    String second = connector.parse(json.readTree(body), Map.of()).orElseThrow().deliveryId();
    assertEquals(first, second, "the derived delivery id must be deterministic");

    String other = connector.parse(json.readTree(body.replace("app", "other")), Map.of()).orElseThrow().deliveryId();
    assertNotEquals(first, other, "different payloads must not collide on one delivery id");
  }
}
