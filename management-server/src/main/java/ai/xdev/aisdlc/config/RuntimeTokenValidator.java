package ai.xdev.aisdlc.config;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Separates the human control-plane audience from the agent-runtime audience at the resource-server boundary.
 *
 * <p>A token carrying the {@code agent_runtime} realm role must present the dedicated runtime audience, must not also
 * carry a human realm role, and must come from the configured authorized party when one is set. Because the runtime
 * audience has no default, an unconfigured deployment rejects every runtime token instead of exposing the internal
 * surface.
 */
public class RuntimeTokenValidator implements OAuth2TokenValidator<Jwt> {
  private static final String INVALID_TOKEN = "invalid_token";

  private final RuntimeAudienceProperties audiences;

  public RuntimeTokenValidator(RuntimeAudienceProperties audiences) {
    this.audiences = audiences;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    List<String> roles = SecurityConfig.RealmRoleConverter.realmRoles(token);
    boolean runtimeWorkload = roles.contains(SecurityConfig.AGENT_RUNTIME_ROLE);
    boolean humanRole = roles.stream().anyMatch(SecurityConfig.HUMAN_ROLES::contains);
    if (runtimeWorkload && humanRole) {
      return failure("A runtime workload token must not also carry a human realm role");
    }
    if (runtimeWorkload) {
      if (!audiences.isRuntimeAudienceConfigured()) {
        return failure("The runtime audience is not configured; runtime tokens are rejected");
      }
      if (!audienceContains(token, audiences.getRuntime())) {
        return failure("The required runtime audience is missing");
      }
      if (audiences.isRuntimeAuthorizedPartyEnforced() && !audiences.getRuntimeAuthorizedParty().equals(token.getClaimAsString("azp"))) {
        return failure("The runtime token was issued to an unexpected authorized party");
      }
      return OAuth2TokenValidatorResult.success();
    }
    if (audiences.isControlPlaneAudienceEnforced() && !audienceContains(token, audiences.getControlPlane())) {
      return failure("The required control-plane audience is missing");
    }
    if (audiences.isRuntimeAudienceConfigured() && audienceContains(token, audiences.getRuntime())) {
      return failure("A control-plane token must not carry the runtime audience");
    }
    return OAuth2TokenValidatorResult.success();
  }

  private static boolean audienceContains(Jwt token, String expected) {
    List<String> audience = token.getAudience();
    return audience != null && audience.contains(expected);
  }

  private static OAuth2TokenValidatorResult failure(String description) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error(INVALID_TOKEN, description, null));
  }
}
