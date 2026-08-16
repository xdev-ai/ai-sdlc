package ai.xdev.aisdlc.config;

import ai.xdev.aisdlc.service.FailClosedProviderCredentialResolver;
import ai.xdev.aisdlc.service.MountedSecretProviderCredentialResolver;
import ai.xdev.aisdlc.service.ProviderCredentialResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the provider proxy to a secret-manager mount when an operator configures one, and keeps the fail-closed
 * resolver otherwise. There is no configuration in which a provider credential is read from the control-plane
 * database, the process environment, a request, or an unreviewed fallback path.
 */
@Configuration
public class RuntimeAiCredentialConfiguration {
  @Bean
  ProviderCredentialResolver providerCredentialResolver(
      @Value("${aisdlc.runtime-ai.credentials.mount-path:}") String mountPath) {
    return resolverFor(mountPath);
  }

  /** Package-visible for the configuration contract test. */
  static ProviderCredentialResolver resolverFor(String mountPath) {
    if (mountPath == null || mountPath.isBlank()) return new FailClosedProviderCredentialResolver();
    Path resolved = Path.of(mountPath).toAbsolutePath().normalize();
    if (!Files.isDirectory(resolved)) {
      throw new IllegalStateException("aisdlc.runtime-ai.credentials.mount-path must be an existing directory");
    }
    return new MountedSecretProviderCredentialResolver(resolved);
  }
}
