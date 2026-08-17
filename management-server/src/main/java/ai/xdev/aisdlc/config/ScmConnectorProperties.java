package ai.xdev.aisdlc.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-connector inbound credentials and header names.
 *
 * <p>Header names are configurable with documented defaults rather than compiled in. The defaults reflect each
 * provider's published webhook behaviour, but a provider can change a header or a deployment can sit behind a proxy
 * that renames one; making that a configuration change instead of a code change is the difference between a config
 * edit and a release.
 *
 * <p>Every connector is disabled until its secret is set. An unconfigured connector rejects every request rather
 * than accepting unverified input.
 */
@ConfigurationProperties(prefix = "aisdlc.scm")
public class ScmConnectorProperties {
  public static class Connector {
    private String secret = "";
    private String signatureHeader = "";
    private String eventHeader = "";
    private String deliveryHeader = "";
    private String apiBaseUrl = "";
    private String apiToken = "";
    private String apiUser = "";
    private String organization = "";
    private String statusContext = "ai-sdlc/policy";
    private String detailsUrlTemplate = "";
    private Duration requestTimeout = Duration.ofSeconds(10);

    public String getSecret() { return secret; } public void setSecret(String secret) { this.secret = secret == null ? "" : secret.trim(); }
    public String getSignatureHeader() { return signatureHeader; } public void setSignatureHeader(String signatureHeader) { this.signatureHeader = signatureHeader == null ? "" : signatureHeader.trim(); }
    public String getEventHeader() { return eventHeader; } public void setEventHeader(String eventHeader) { this.eventHeader = eventHeader == null ? "" : eventHeader.trim(); }
    public String getDeliveryHeader() { return deliveryHeader; } public void setDeliveryHeader(String deliveryHeader) { this.deliveryHeader = deliveryHeader == null ? "" : deliveryHeader.trim(); }

    /** Provider API root for outbound policy feedback, for example {@code https://gitlab.com}. */
    public String getApiBaseUrl() { return apiBaseUrl; } public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim().replaceAll("/+$", ""); }
    /** Outbound credential. Separate from {@code secret}: the inbound webhook secret must not double as an API token. */
    public String getApiToken() { return apiToken; } public void setApiToken(String apiToken) { this.apiToken = apiToken == null ? "" : apiToken.trim(); }
    /** Account identifier for providers whose API token is the password half of Basic auth, such as a Jira user email. */
    public String getApiUser() { return apiUser; } public void setApiUser(String apiUser) { this.apiUser = apiUser == null ? "" : apiUser.trim(); }
    /** Azure DevOps organization. Its status URL needs an organization that appears nowhere in the webhook payload. */
    public String getOrganization() { return organization; } public void setOrganization(String organization) { this.organization = organization == null ? "" : organization.trim(); }
    /** Status context or check name shown on the provider side. */
    public String getStatusContext() { return statusContext; } public void setStatusContext(String statusContext) { this.statusContext = statusContext == null || statusContext.isBlank() ? "ai-sdlc/policy" : statusContext.trim(); }
    /** Deep link back into the platform; {@code {externalId}} is replaced with the SCM event id. */
    public String getDetailsUrlTemplate() { return detailsUrlTemplate; } public void setDetailsUrlTemplate(String detailsUrlTemplate) { this.detailsUrlTemplate = detailsUrlTemplate == null ? "" : detailsUrlTemplate.trim(); }
    public Duration getRequestTimeout() { return requestTimeout; } public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout; }

    public boolean isConfigured() { return !secret.isBlank(); }

    /**
     * Outbound is configured independently of inbound. A deployment may ingest events from a provider long before it
     * is willing to let the platform write statuses back, and enabling ingestion must not silently enable writes.
     */
    public boolean isOutboundConfigured() { return !apiBaseUrl.isBlank() && !apiToken.isBlank(); }

    public String detailsUrl(String externalId) {
      return detailsUrlTemplate.isBlank() ? null : detailsUrlTemplate.replace("{externalId}", externalId == null ? "" : externalId);
    }
  }

  private final Map<String, Connector> connectors = new LinkedHashMap<>();

  public Map<String, Connector> getConnectors() { return connectors; }

  /** Returns the configuration for a connector key, never null, so an unconfigured provider simply rejects. */
  public Connector forKey(String key) {
    return connectors.computeIfAbsent(key, unused -> new Connector());
  }
}
