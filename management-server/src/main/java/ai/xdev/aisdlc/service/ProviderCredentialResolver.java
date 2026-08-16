package ai.xdev.aisdlc.service;

import javax.net.ssl.SSLContext;

/** Resolves opaque provider-secret references at the runtime boundary; callers never receive a stored secret. */
public interface ProviderCredentialResolver {
  record CredentialMaterial(String authorizationHeader, SSLContext sslContext) {}
  CredentialMaterial resolve(String credentialReference, String mtlsReference, boolean requireMtls);
}
