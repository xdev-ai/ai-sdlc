package ai.xdev.aisdlc.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.xdev.aisdlc.service.FailClosedProviderCredentialResolver;
import ai.xdev.aisdlc.service.MountedSecretProviderCredentialResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeAiCredentialConfigurationTest {
  @Test
  void keepsTheFailClosedResolverWhenNoMountIsConfigured() {
    assertInstanceOf(FailClosedProviderCredentialResolver.class, RuntimeAiCredentialConfiguration.resolverFor(null));
    assertInstanceOf(FailClosedProviderCredentialResolver.class, RuntimeAiCredentialConfiguration.resolverFor(""));
    assertInstanceOf(FailClosedProviderCredentialResolver.class, RuntimeAiCredentialConfiguration.resolverFor("   "));
  }

  @Test
  void bindsTheMountedResolverOnlyToAnExistingDirectory(@TempDir Path mount) {
    assertInstanceOf(MountedSecretProviderCredentialResolver.class,
        RuntimeAiCredentialConfiguration.resolverFor(mount.toString()));
    assertThrows(IllegalStateException.class,
        () -> RuntimeAiCredentialConfiguration.resolverFor(mount.resolve("absent").toString()));
  }
}
