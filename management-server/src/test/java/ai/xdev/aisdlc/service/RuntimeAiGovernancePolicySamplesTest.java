package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeAiGovernancePolicySamplesTest {
  private static final List<String> SAMPLE_FILES = List.of(
      "workload-model-allowlist.json",
      "input-tool-containment.json",
      "output-approval-gate.json",
      "emergency-override.json");
  private final PolicyExpressionEngine engine = new PolicyExpressionEngine();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void runtimeAiGovernanceSamplesCompileAndMatchEveryFixture() throws Exception {
    for (String filename : SAMPLE_FILES) {
      JsonNode bundle = load(filename);
      String expression = bundle.required("expression").asText();
      engine.validate(expression);
      assertTrue(bundle.required("dryRunDefault").asBoolean(), filename + " must begin in dry-run mode");

      for (JsonNode fixture : bundle.withArray("fixtures")) {
        Map<String, Object> context = mapper.convertValue(fixture.required("context"), new TypeReference<Map<String, Object>>() {});
        Object actual = engine.evaluate(expression, context);
        assertEquals(Boolean.valueOf(fixture.required("expected").booleanValue()), actual, filename + ": " + fixture.required("name").asText());
      }
    }
  }

  private JsonNode load(String filename) throws Exception {
    String path = "runtime-ai-governance-policies/" + filename;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("Missing policy sample resource " + path);
      return mapper.readTree(input);
    }
  }
}
