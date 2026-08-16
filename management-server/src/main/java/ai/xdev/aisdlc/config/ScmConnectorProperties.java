package ai.xdev.aisdlc.config;

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

    public String getSecret() { return secret; } public void setSecret(String secret) { this.secret = secret == null ? "" : secret.trim(); }
    public String getSignatureHeader() { return signatureHeader; } public void setSignatureHeader(String signatureHeader) { this.signatureHeader = signatureHeader == null ? "" : signatureHeader.trim(); }
    public String getEventHeader() { return eventHeader; } public void setEventHeader(String eventHeader) { this.eventHeader = eventHeader == null ? "" : eventHeader.trim(); }
    public String getDeliveryHeader() { return deliveryHeader; } public void setDeliveryHeader(String deliveryHeader) { this.deliveryHeader = deliveryHeader == null ? "" : deliveryHeader.trim(); }
    public boolean isConfigured() { return !secret.isBlank(); }
  }

  private final Map<String, Connector> connectors = new LinkedHashMap<>();

  public Map<String, Connector> getConnectors() { return connectors; }

  /** Returns the configuration for a connector key, never null, so an unconfigured provider simply rejects. */
  public Connector forKey(String key) {
    return connectors.computeIfAbsent(key, unused -> new Connector());
  }
}
