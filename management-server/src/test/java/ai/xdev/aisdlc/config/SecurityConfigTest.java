package ai.xdev.aisdlc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {
  private static List<String> authorities(Jwt jwt) {
    return new SecurityConfig.RealmRoleConverter().convert(jwt).stream().map(value -> value.getAuthority()).toList();
  }

  private static Jwt jwt(Object rolesClaim) {
    Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "subject-1")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    if (rolesClaim instanceof Map<?, ?> realmAccess) builder.claim("realm_access", realmAccess);
    else builder.claim("roles", rolesClaim);
    return builder.build();
  }

  @Test
  void mapsOnlySupportedKeycloakRealmRoles() {
    var authorities = authorities(jwt(List.of("developer", "untrusted", "reviewer", "viewer")));
    assertTrue(authorities.contains("ROLE_developer"));
    assertTrue(authorities.contains("ROLE_reviewer"));
    assertTrue(authorities.contains("ROLE_viewer"));
    assertTrue(authorities.stream().noneMatch(value -> value.contains("untrusted")));
  }

  @Test
  void mapsAnAgentWorkloadToTheRuntimeAuthorityOnly() {
    assertEquals(List.of("ROLE_agent_runtime"), authorities(jwt(List.of("agent_runtime"))));
    assertEquals(List.of("ROLE_agent_runtime"), authorities(jwt(Map.of("roles", List.of("agent_runtime")))));
  }

  @Test
  void grantsNothingToATokenClaimingBothWorkloadAndHumanIdentity() {
    assertEquals(List.of(), authorities(jwt(List.of("agent_runtime", "admin"))));
    assertEquals(List.of(), authorities(jwt(List.of("viewer", "agent_runtime"))));
  }

  @Test
  void readsRealmRolesFromEitherClaimShape() {
    assertEquals(List.of("admin"), SecurityConfig.RealmRoleConverter.realmRoles(jwt(List.of("admin", "untrusted"))));
    assertEquals(List.of("reviewer"), SecurityConfig.RealmRoleConverter.realmRoles(jwt(Map.of("roles", List.of("reviewer")))));
    assertEquals(List.of(), SecurityConfig.RealmRoleConverter.realmRoles(jwt(List.of("untrusted"))));
  }
}
