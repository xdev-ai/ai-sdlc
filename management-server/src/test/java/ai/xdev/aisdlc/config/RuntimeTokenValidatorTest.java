package ai.xdev.aisdlc.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RuntimeTokenValidatorTest {
  private static RuntimeAudienceProperties audiences(String controlPlane, String runtime, String authorizedParty) {
    RuntimeAudienceProperties properties = new RuntimeAudienceProperties();
    properties.setControlPlane(controlPlane);
    properties.setRuntime(runtime);
    properties.setRuntimeAuthorizedParty(authorizedParty);
    return properties;
  }

  private static Jwt token(List<String> roles, List<String> audience, String authorizedParty) {
    Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("subject-1")
        .claim("roles", roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    if (audience != null) builder.audience(audience);
    if (authorizedParty != null) builder.claim("azp", authorizedParty);
    return builder.build();
  }

  @Test
  void rejectsEveryRuntimeTokenWhileTheRuntimeAudienceIsUnconfigured() {
    var validator = new RuntimeTokenValidator(audiences("", "", ""));
    assertTrue(validator.validate(token(List.of("agent_runtime"), List.of("aisdlc-runtime"), null)).hasErrors());
  }

  @Test
  void acceptsARuntimeWorkloadTokenCarryingTheRuntimeAudience() {
    var validator = new RuntimeTokenValidator(audiences("", "aisdlc-runtime", ""));
    assertFalse(validator.validate(token(List.of("agent_runtime"), List.of("aisdlc-runtime"), null)).hasErrors());
  }

  @Test
  void rejectsARuntimeTokenWithoutTheRuntimeAudience() {
    var validator = new RuntimeTokenValidator(audiences("", "aisdlc-runtime", ""));
    assertTrue(validator.validate(token(List.of("agent_runtime"), List.of("aisdlc-management"), null)).hasErrors());
    assertTrue(validator.validate(token(List.of("agent_runtime"), null, null)).hasErrors());
  }

  @Test
  void rejectsATokenClaimingBothWorkloadAndHumanIdentity() {
    var validator = new RuntimeTokenValidator(audiences("", "aisdlc-runtime", ""));
    assertTrue(validator.validate(token(List.of("agent_runtime", "admin"), List.of("aisdlc-runtime"), null)).hasErrors());
  }

  @Test
  void rejectsARuntimeTokenFromAnUnexpectedAuthorizedParty() {
    var validator = new RuntimeTokenValidator(audiences("", "aisdlc-runtime", "aisdlc-agent-runtime"));
    assertFalse(validator.validate(token(List.of("agent_runtime"), List.of("aisdlc-runtime"), "aisdlc-agent-runtime")).hasErrors());
    assertTrue(validator.validate(token(List.of("agent_runtime"), List.of("aisdlc-runtime"), "aisdlc-cli")).hasErrors());
    assertTrue(validator.validate(token(List.of("agent_runtime"), List.of("aisdlc-runtime"), null)).hasErrors());
  }

  @Test
  void rejectsAHumanTokenThatCarriesTheRuntimeAudience() {
    var validator = new RuntimeTokenValidator(audiences("", "aisdlc-runtime", ""));
    assertTrue(validator.validate(token(List.of("admin"), List.of("aisdlc-runtime"), null)).hasErrors());
  }

  @Test
  void enforcesTheControlPlaneAudienceOnlyWhenItIsConfigured() {
    assertFalse(new RuntimeTokenValidator(audiences("", "", "")).validate(token(List.of("admin"), null, null)).hasErrors());
    var enforcing = new RuntimeTokenValidator(audiences("aisdlc-management", "", ""));
    assertTrue(enforcing.validate(token(List.of("admin"), null, null)).hasErrors());
    assertFalse(enforcing.validate(token(List.of("admin"), List.of("aisdlc-management"), null)).hasErrors());
  }
}
