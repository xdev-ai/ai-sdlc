package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The portal's OIDC client registration must be able to verify an ID token signature.
 *
 * <p>Without {@code jwk-set-uri}, Spring Security has no JWS verifier and every login fails <em>after</em> a
 * successful authorization-code exchange with
 * {@code [missing_signature_verifier] Failed to find a Signature Verifier for Client Registration}, redirecting the
 * user to {@code /session-expired}. The whole authenticated portal was unreachable in every deployment.
 *
 * <p>It was invisible from both ends. Keycloak recorded {@code LOGIN} and {@code CODE_TO_TOKEN} with no error, so the
 * identity provider reported a clean login; and the portal's own failure handler produced the same page as an
 * ordinary expired session, so "sign in again" looked like the remedy while looping forever.
 *
 * <p>Asserting on the YAML rather than on a started context is deliberate: this is a configuration omission, and the
 * check has to fail at build time on the file a developer edits.
 */
class PortalOidcRegistrationTest {
  @SuppressWarnings("unchecked")
  private static Map<String, Object> keycloakProvider() {
    try (InputStream yaml = PortalOidcRegistrationTest.class.getClassLoader().getResourceAsStream("application.yml")) {
      assertNotNull(yaml, "application.yml must be on the test classpath");
      Map<String, Object> root = new Yaml().load(yaml);
      Map<String, Object> node = root;
      for (String key : new String[] {"spring", "security", "oauth2", "client", "provider", "keycloak"}) {
        node = (Map<String, Object>) node.get(key);
        assertNotNull(node, "missing configuration node: " + key);
      }
      return node;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("could not read application.yml", error);
    }
  }

  @Test void theProviderDeclaresAJwkSetUriSoIdTokenSignaturesCanBeVerified() {
    Object jwkSetUri = keycloakProvider().get("jwk-set-uri");

    assertNotNull(jwkSetUri, "jwk-set-uri is required; without it every portal login fails with missing_signature_verifier");
    assertTrue(jwkSetUri.toString().contains("/protocol/openid-connect/certs"),
        "jwk-set-uri must point at the realm's JWKS endpoint, was: " + jwkSetUri);
  }

  @Test void serverToServerCallsUseTheInternalHostAndTheBrowserRedirectUsesThePublicOne() {
    Map<String, Object> provider = keycloakProvider();

    // The container cannot resolve the browser-facing hostname, so token, userinfo and JWKS must use the internal one.
    for (String serverSide : new String[] {"token-uri", "user-info-uri", "jwk-set-uri"}) {
      assertTrue(provider.get(serverSide).toString().contains("KEYCLOAK_INTERNAL_BASE_URL"),
          serverSide + " must default to the internal base URL, was: " + provider.get(serverSide));
    }
    assertTrue(provider.get("authorization-uri").toString().contains("KEYCLOAK_PUBLIC_BASE_URL"),
        "the browser must be redirected to the public host");
  }

  @Test void theIssuerMatchesTheHostTheBrowserAuthorizedAgainst() {
    // The id_token `iss` claim carries the public issuer. Validating it against the internal host would reject every
    // token, so the issuer has to be stated explicitly rather than inferred from jwk-set-uri.
    Object issuer = keycloakProvider().get("issuer-uri");

    assertNotNull(issuer, "issuer-uri must be declared for id_token issuer validation");
    assertTrue(issuer.toString().contains("KEYCLOAK_PUBLIC_BASE_URL"),
        "issuer-uri must use the public base URL, was: " + issuer);
  }
}
