package ai.xdev.aisdlc.service;

import org.springframework.stereotype.Component;

/**
 * Safe default until a deployment binds the proxy to its approved secret manager. It intentionally
 * has no environment-variable, database, file-system, or caller-provided credential fallback.
 */
@Component
public class FailClosedProviderCredentialResolver implements ProviderCredentialResolver {
  @Override
  public CredentialMaterial resolve(String credentialReference, String mtlsReference, boolean requireMtls) {
    throw new IllegalStateException("Provider credential resolver is not configured");
  }
}
