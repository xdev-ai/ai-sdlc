package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.cel.common.CelValidationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyExpressionEngineTest {
  private final PolicyExpressionEngine engine = new PolicyExpressionEngine();

  @Test void evaluatesTypedBooleanPolicyAgainstOnlyDeclaredContext() throws Exception {
    Object value = engine.evaluate("context.release.approved && context.security.highFindings == 0", Map.of("release", Map.of("approved", true), "security", Map.of("highFindings", 0L)));
    assertEquals(Boolean.TRUE, value);
  }

  @Test void rejectsUndefinedIdentifiersAtCompilation() {
    assertThrows(CelValidationException.class, () -> engine.validate("system.exit(0)"));
  }

  @Test void permitsNonBooleanResultsForTheServiceToFailClosed() throws Exception {
    assertEquals("not-a-decision", engine.evaluate("'not-a-decision'", Map.of()));
  }
}
