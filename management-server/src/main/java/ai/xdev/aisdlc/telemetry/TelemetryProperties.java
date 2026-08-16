package ai.xdev.aisdlc.telemetry;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Versioned P3.1 telemetry configuration model.
 *
 * <p>Telemetry is disabled and exporterless by default, so an unconfigured deployment behaves exactly as it did before
 * P3.1. When it is enabled, validation rejects an insecure or ambiguous exporter endpoint, an unknown deployment
 * environment, an out-of-range sample ratio, a contract-version mismatch, and any operator-supplied resource attribute
 * outside {@link TelemetryAttributeContract#RESOURCE_ATTRIBUTE_KEYS}.
 */
@ConfigurationProperties(prefix = "aisdlc.telemetry")
public class TelemetryProperties {
  /** Deployment environment values permitted as a resource attribute; a tenant or project value is never accepted. */
  public static final Set<String> SUPPORTED_ENVIRONMENTS = Set.of("development", "staging", "production");

  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
  private static final Duration MIN_EXPORT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration MAX_EXPORT_TIMEOUT = Duration.ofSeconds(30);

  private boolean enabled = false;
  private String contractVersion = TelemetryAttributeContract.CONTRACT_VERSION;
  private String serviceName = "ai-sdlc-management-server";
  private String serviceNamespace = "ai-sdlc";
  private String serviceVersion = "";
  private String serviceInstanceId = "";
  private String environment = "development";
  private String exporterEndpoint = "";
  private Duration exporterTimeout = Duration.ofSeconds(10);
  private double traceSampleRatio = 0.1d;
  private boolean acceptRemoteTraceContext = true;
  private Map<String, String> resourceAttributes = new LinkedHashMap<>();

  public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getContractVersion() { return contractVersion; } public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
  public String getServiceName() { return serviceName; } public void setServiceName(String serviceName) { this.serviceName = serviceName; }
  public String getServiceNamespace() { return serviceNamespace; } public void setServiceNamespace(String serviceNamespace) { this.serviceNamespace = serviceNamespace; }
  public String getServiceVersion() { return serviceVersion; } public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
  public String getServiceInstanceId() { return serviceInstanceId; } public void setServiceInstanceId(String serviceInstanceId) { this.serviceInstanceId = serviceInstanceId; }
  public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
  public String getExporterEndpoint() { return exporterEndpoint; } public void setExporterEndpoint(String exporterEndpoint) { this.exporterEndpoint = exporterEndpoint; }
  public Duration getExporterTimeout() { return exporterTimeout; } public void setExporterTimeout(Duration exporterTimeout) { this.exporterTimeout = exporterTimeout; }
  public double getTraceSampleRatio() { return traceSampleRatio; } public void setTraceSampleRatio(double traceSampleRatio) { this.traceSampleRatio = traceSampleRatio; }
  public boolean isAcceptRemoteTraceContext() { return acceptRemoteTraceContext; } public void setAcceptRemoteTraceContext(boolean acceptRemoteTraceContext) { this.acceptRemoteTraceContext = acceptRemoteTraceContext; }
  public Map<String, String> getResourceAttributes() { return resourceAttributes; } public void setResourceAttributes(Map<String, String> resourceAttributes) { this.resourceAttributes = resourceAttributes == null ? new LinkedHashMap<>() : resourceAttributes; }

  /** True only when an exporter may be constructed; a disabled or endpointless configuration exports nothing. */
  public boolean isExportConfigured() { return enabled && !exporterEndpoint.isBlank(); }

  /** Returns every configuration violation; an empty list means the configuration is safe to activate. */
  public List<String> validate() {
    List<String> violations = new ArrayList<>();
    if (!TelemetryAttributeContract.CONTRACT_VERSION.equals(contractVersion)) {
      violations.add("aisdlc.telemetry.contract-version must be " + TelemetryAttributeContract.CONTRACT_VERSION);
    }
    if (!SUPPORTED_ENVIRONMENTS.contains(environment)) {
      violations.add("aisdlc.telemetry.environment must be one of " + SUPPORTED_ENVIRONMENTS);
    }
    if (serviceName == null || !serviceName.matches("[a-z0-9][a-z0-9-]{1,62}")) {
      violations.add("aisdlc.telemetry.service-name must be a lower-case service identifier");
    }
    if (serviceNamespace == null || !serviceNamespace.matches("[a-z0-9][a-z0-9-]{1,62}")) {
      violations.add("aisdlc.telemetry.service-namespace must be a lower-case namespace identifier");
    }
    if (!(traceSampleRatio >= 0.0d && traceSampleRatio <= 1.0d)) {
      violations.add("aisdlc.telemetry.trace-sample-ratio must be between 0.0 and 1.0");
    }
    Set<String> rejected = TelemetryAttributeContract.rejectedResourceKeys(resourceAttributes);
    if (!rejected.isEmpty()) {
      violations.add("aisdlc.telemetry.resource-attributes rejected outside the telemetry contract: " + rejected);
    }
    if (enabled) {
      if (exporterEndpoint.isBlank()) {
        violations.add("aisdlc.telemetry.exporter-endpoint is required when telemetry is enabled");
      } else {
        violations.addAll(validateEndpoint(exporterEndpoint));
      }
      if (exporterTimeout == null || exporterTimeout.compareTo(MIN_EXPORT_TIMEOUT) < 0 || exporterTimeout.compareTo(MAX_EXPORT_TIMEOUT) > 0) {
        violations.add("aisdlc.telemetry.exporter-timeout must be between " + MIN_EXPORT_TIMEOUT + " and " + MAX_EXPORT_TIMEOUT);
      }
    }
    return List.copyOf(violations);
  }

  private List<String> validateEndpoint(String value) {
    List<String> violations = new ArrayList<>();
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException exception) {
      return List.of("aisdlc.telemetry.exporter-endpoint is not a valid absolute URI");
    }
    if (!uri.isAbsolute() || uri.getHost() == null) {
      return List.of("aisdlc.telemetry.exporter-endpoint must be an absolute URI with a host");
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    boolean loopback = LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    if (!"https".equals(scheme) && !("http".equals(scheme) && loopback && "development".equals(environment))) {
      violations.add("aisdlc.telemetry.exporter-endpoint must use https outside a development loopback collector");
    }
    if (uri.getUserInfo() != null) violations.add("aisdlc.telemetry.exporter-endpoint must not embed user information");
    if (uri.getQuery() != null) violations.add("aisdlc.telemetry.exporter-endpoint must not carry a query string");
    if (uri.getFragment() != null) violations.add("aisdlc.telemetry.exporter-endpoint must not carry a fragment");
    return violations;
  }

  /** Fails startup rather than exporting telemetry through an unreviewed configuration. */
  @PostConstruct
  public void verify() {
    List<String> violations = validate();
    if (!violations.isEmpty()) {
      throw new IllegalStateException("Invalid telemetry configuration: " + String.join("; ", violations));
    }
  }
}
