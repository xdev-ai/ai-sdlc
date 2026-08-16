package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;

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
}
