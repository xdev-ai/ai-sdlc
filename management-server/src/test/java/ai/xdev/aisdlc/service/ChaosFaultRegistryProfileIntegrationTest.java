package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ChaosFaultRegistryProfileIntegrationTest {
  @Test
  void defaultProfileDoesNotRegisterChaosFaultRegistry() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(ChaosFaultRegistry.class);
      context.refresh();
      assertFalse(context.containsBean("chaosFaultRegistry"));
      assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class, () -> context.getBean(ChaosFaultRegistry.class));
    }
  }

  @Test
  void explicitChaosProfileRegistersTheIsolatedFaultRegistry() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("chaos");
      context.register(ChaosFaultRegistry.class);
      context.refresh();
      var registry = context.getBean(ChaosFaultRegistry.class);
      registry.enable(ChaosFaultRegistry.Component.POLICY_ENGINE, ChaosFaultRegistry.Mode.TIMEOUT);
      assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> registry.check(ChaosFaultRegistry.Component.POLICY_ENGINE));
    }
  }
}
