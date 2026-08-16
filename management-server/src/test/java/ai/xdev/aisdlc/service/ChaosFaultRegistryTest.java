package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

class ChaosFaultRegistryTest {
  @Test void enabledFaultFailsOnlyForItsDeclaredComponentAndClearRecovers() {
    ChaosFaultRegistry registry = new ChaosFaultRegistry();
    registry.enable(ChaosFaultRegistry.Component.POLICY_ENGINE, ChaosFaultRegistry.Mode.TIMEOUT);
    assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> registry.check(ChaosFaultRegistry.Component.POLICY_ENGINE));
    assertDoesNotThrow(() -> registry.check(ChaosFaultRegistry.Component.EVIDENCE_STORAGE));
    registry.clear();
    assertDoesNotThrow(() -> registry.check(ChaosFaultRegistry.Component.POLICY_ENGINE));
  }

  @Test void policyEngineEvaluatesThroughTheChaosSeamOnlyWhenTheFaultIsEnabled() throws Exception {
    ChaosFaultRegistry registry = new ChaosFaultRegistry();
    ObjectProvider<ChaosFaultRegistry> provider = new ObjectProvider<>() {
      @Override public ChaosFaultRegistry getObject() { return registry; }
    };
    PolicyExpressionEngine engine = new PolicyExpressionEngine(provider);
    assertEquals(Boolean.TRUE, engine.evaluate("true", java.util.Map.of()));
    registry.enable(ChaosFaultRegistry.Component.POLICY_ENGINE, ChaosFaultRegistry.Mode.TIMEOUT);
    assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> engine.evaluate("true", java.util.Map.of()));
  }
}
