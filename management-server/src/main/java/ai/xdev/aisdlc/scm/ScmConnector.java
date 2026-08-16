package ai.xdev.aisdlc.scm;

import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;

/**
 * The versioned inbound contract every SCM and work-management connector implements.
 *
 * <p>A connector is responsible for exactly three things: proving the request came from its provider, extracting a
 * stable delivery identifier, and mapping the provider's payload onto the platform's neutral event shape. It never
 * decides authorization, never writes to the database, and never calls the provider back. Everything downstream —
 * repository linking, idempotency, correlation, audit — is shared, so a new connector cannot invent its own rules
 * for the parts that matter.
 *
 * <p>The delivery identifier is the idempotency key. A provider that does not supply one forces the connector to
 * derive a deterministic one from the payload, because at-least-once delivery is the norm and a replay must not
 * create a second event.
 */
public interface ScmConnector {
  /** Contract version of the inbound shape. A breaking change to {@link InboundEvent} increments this. */
  String CONTRACT_VERSION = "scm.inbound.v1";

  ScmProvider provider();

  /** True when this connector is configured with the credential it needs to verify a request. */
  boolean isConfigured();

  /**
   * Verifies the request came from the provider. Implementations compare in constant time and return false rather
   * than throwing, so a malformed header is a rejection and not a 500.
   *
   * @param headers header names lower-cased by the caller, so a provider's casing cannot cause a silent miss
   */
  boolean verify(byte[] payload, Map<String, String> headers);

  /**
   * Maps a verified payload onto the neutral event shape.
   *
   * @return empty when the payload is not an event this connector represents; the caller records it as ignored
   *     rather than guessing
   */
  Optional<InboundEvent> parse(JsonNode payload, Map<String, String> headers);

  /**
   * One inbound event in provider-neutral terms.
   *
   * @param deliveryId provider delivery identifier, or a deterministic digest when the provider sends none
   * @param eventType neutral event classification
   * @param repositoryFullName the value a project links against, for example {@code group/project}
   * @param action provider action verb, or null
   * @param ref branch or tag reference, or null
   * @param commitSha head commit, or null
   * @param pullRequestNumber merge or pull request number, or null
   * @param externalKey provider-specific correlation key such as a Jira issue key, or null
   */
  record InboundEvent(String deliveryId, ScmEventType eventType, String repositoryFullName, String action,
                      String ref, String commitSha, Integer pullRequestNumber, String externalKey) {}
}
