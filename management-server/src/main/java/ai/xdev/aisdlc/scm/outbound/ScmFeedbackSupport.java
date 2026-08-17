package ai.xdev.aisdlc.scm.outbound;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.scm.outbound.ScmFeedbackPublisher.ScmFeedbackException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HTTP and encoding primitives shared by the outbound publishers.
 *
 * <p>The error handling here exists to keep provider responses out of the platform's logs and audit trail. A failed
 * publish reports the status code and nothing else: provider error bodies routinely echo the request, and the
 * request contains a token in a header and a repository path that may itself be sensitive.
 */
@Component
public final class ScmFeedbackSupport {
  /** Provider descriptions are truncated because several providers reject or silently trim long values. */
  static final int MAX_DESCRIPTION = 400;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public ScmFeedbackSupport(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
  }

  public ObjectNode newBody() { return objectMapper.createObjectNode(); }

  /** Sends a JSON request and returns the parsed body, or {@link com.fasterxml.jackson.databind.node.NullNode} when empty. */
  public JsonNode send(String method, URI uri, ObjectNode body, String authorizationHeader,
                       ScmConnectorProperties.Connector config, String providerLabel) {
    try {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(config.getRequestTimeout())
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .header("Authorization", authorizationHeader)
          .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        // The status code only. The response body is not included: providers echo the submitted request, and the
        // request carries an Authorization header and the repository path.
        throw new ScmFeedbackException(providerLabel + " policy feedback returned HTTP " + response.statusCode());
      }
      String payload = response.body();
      return payload == null || payload.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(payload);
    } catch (ScmFeedbackException alreadyDescribed) {
      throw alreadyDescribed;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ScmFeedbackException(providerLabel + " policy feedback was interrupted", interrupted);
    } catch (Exception error) {
      throw new ScmFeedbackException(providerLabel + " policy feedback could not be delivered", error);
    }
  }

  /** Percent-encodes a single path segment. GitLab addresses a project by its URL-encoded {@code group/project}. */
  public static String encodeSegment(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public static String basicAuth(String user, String token) {
    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
  }

  public static String truncate(String value) {
    if (value == null) return "";
    return value.length() <= MAX_DESCRIPTION ? value : value.substring(0, MAX_DESCRIPTION - 1) + "…";
  }

  /**
   * Splits {@code first/second}. Providers that address a repository by two components reject a single-component
   * name rather than guessing, because guessing writes a status onto the wrong repository.
   */
  public static String[] twoPartName(String fullName, String providerLabel, String expectedForm) {
    String[] parts = fullName == null ? new String[0] : fullName.split("/", -1);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      throw new ScmFeedbackException(providerLabel + " requires a repository in " + expectedForm + " form");
    }
    return parts;
  }
}
