package ai.xdev.aisdlc.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryPropertiesTest {
  private static TelemetryProperties enabledProduction(String endpoint) {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setEnabled(true);
    properties.setEnvironment("production");
    properties.setExporterEndpoint(endpoint);
    return properties;
  }

  @Test
  void defaultsToDisabledTelemetryWithNoExporter() {
    TelemetryProperties properties = new TelemetryProperties();
    assertFalse(properties.isEnabled());
    assertFalse(properties.isExportConfigured());
    assertEquals("", properties.getExporterEndpoint());
    assertEquals(TelemetryAttributeContract.CONTRACT_VERSION, properties.getContractVersion());
    assertEquals(List.of(), properties.validate());
  }

  @Test
  void rejectsInsecureProductionExporterEndpoint() {
    List<String> violations = enabledProduction("http://collector.internal:4317").validate();
    assertTrue(violations.stream().anyMatch(violation -> violation.contains("must use https")), violations.toString());
  }

  @Test
  void rejectsPlainHttpLoopbackOutsideDevelopment() {
    TelemetryProperties staging = enabledProduction("http://localhost:4317");
    staging.setEnvironment("staging");
    assertTrue(staging.validate().stream().anyMatch(violation -> violation.contains("must use https")));
  }

  @Test
  void acceptsPlainHttpLoopbackCollectorInDevelopmentOnly() {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setEnabled(true);
    properties.setEnvironment("development");
    properties.setExporterEndpoint("http://localhost:4317");
    assertEquals(List.of(), properties.validate());
    assertTrue(properties.isExportConfigured());
  }

  @Test
  void rejectsEndpointCarryingCredentialsQueryOrFragment() {
    assertTrue(enabledProduction("https://user:secret@collector.internal:4317").validate().stream()
        .anyMatch(violation -> violation.contains("user information")));
    assertTrue(enabledProduction("https://collector.internal:4317?token=abc").validate().stream()
        .anyMatch(violation -> violation.contains("query string")));
    assertTrue(enabledProduction("https://collector.internal:4317#fragment").validate().stream()
        .anyMatch(violation -> violation.contains("fragment")));
  }

  @Test
  void requiresAnEndpointWhenTelemetryIsEnabled() {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setEnabled(true);
    assertTrue(properties.validate().stream().anyMatch(violation -> violation.contains("exporter-endpoint is required")));
    assertFalse(properties.isExportConfigured());
  }

  @Test
  void rejectsUnknownDeploymentEnvironmentSoTenantValuesCannotBecomeResourceAttributes() {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setEnvironment("tenant-acme-prod");
    assertTrue(properties.validate().stream().anyMatch(violation -> violation.contains("environment must be one of")));
  }

  @Test
  void rejectsContractVersionDrift() {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setContractVersion("telemetry.v0");
    assertTrue(properties.validate().stream().anyMatch(violation -> violation.contains("contract-version")));
  }

  @Test
  void rejectsSampleRatioOutsideTheUnitInterval() {
    TelemetryProperties negative = new TelemetryProperties();
    negative.setTraceSampleRatio(-0.01d);
    assertTrue(negative.validate().stream().anyMatch(violation -> violation.contains("trace-sample-ratio")));
    TelemetryProperties excessive = new TelemetryProperties();
    excessive.setTraceSampleRatio(1.5d);
    assertTrue(excessive.validate().stream().anyMatch(violation -> violation.contains("trace-sample-ratio")));
  }

  @Test
  void rejectsExporterTimeoutOutsideTheBoundedRange() {
    TelemetryProperties properties = enabledProduction("https://collector.internal:4317");
    properties.setExporterTimeout(Duration.ofMinutes(5));
    assertTrue(properties.validate().stream().anyMatch(violation -> violation.contains("exporter-timeout")));
  }

  @Test
  void rejectsOperatorSuppliedResourceAttributesOutsideTheContract() {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setResourceAttributes(Map.of("tenant.id", "acme", "service.name", "override"));
    List<String> violations = properties.validate();
    assertTrue(violations.stream().anyMatch(violation -> violation.contains("tenant.id")), violations.toString());
    assertTrue(violations.stream().noneMatch(violation -> violation.contains("[service.name")), violations.toString());
  }

  @Test
  void startupFailsClosedOnAnInvalidConfiguration() {
    TelemetryProperties properties = enabledProduction("http://collector.internal:4317");
    IllegalStateException failure = assertThrows(IllegalStateException.class, properties::verify);
    assertTrue(failure.getMessage().contains("Invalid telemetry configuration"));
  }

  @Test
  void startupSucceedsOnTheShippedDefaults() {
    new TelemetryProperties().verify();
  }
}
