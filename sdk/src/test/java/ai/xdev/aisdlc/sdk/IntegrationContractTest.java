package ai.xdev.aisdlc.sdk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IntegrationContractTest {
  @Test
  void sourceContractContainsOnlyVersionedProjectScopedOperations() throws Exception {
    String contract = Files.readString(Path.of("openapi", "aisdlc-integration-v1.yaml"));
    assertTrue(contract.contains("openapi: 3.1.0"));
    assertTrue(contract.contains("/api/v1/projects/{projectId}/scm-events"));
    assertTrue(contract.contains("/api/v1/projects/{projectId}/risk-intelligence/latest"));
    assertTrue(contract.contains("bearerAuth"));
  }
}
