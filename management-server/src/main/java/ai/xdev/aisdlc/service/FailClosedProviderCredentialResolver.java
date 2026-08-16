package ai.xdev.aisdlc.service;

/**
 * Safe default until a deployment binds the proxy to its approved secret manager. It intentionally
 * has no environment-variable, database, file-system, or caller-provided credential fallback.
 *
 * <p>{@code RuntimeAiCredentialConfiguration} registers this bean whenever no secret-manager mount is configured.
 */
public class FailClosedProviderCredentialResolver implements ProviderCredentialResolver {
  @Override
  public CredentialMaterial resolve(String credentialReference, String mtlsReference, boolean requireMtls) {
    throw new IllegalStateException("Provider credential resolver is not configured");
  }
}
