package ai.xdev.aisdlc.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {
  @Test
  void mapsOnlySupportedKeycloakRealmRoles() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "subject-1").claim("roles", List.of("developer", "untrusted", "reviewer", "viewer")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    var authorities = new SecurityConfig.RealmRoleConverter().convert(jwt).stream().map(value -> value.getAuthority()).toList();
    assertTrue(authorities.contains("ROLE_developer"));
    assertTrue(authorities.contains("ROLE_reviewer"));
    assertTrue(authorities.contains("ROLE_viewer"));
    assertTrue(authorities.stream().noneMatch(value -> value.contains("untrusted")));
  }
}
