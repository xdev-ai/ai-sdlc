package ai.xdev.aisdlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audience and authorized-party expectations for the resource-server boundary.
 *
 * <p>The runtime audience has no default: until an operator configures it, no {@code agent_runtime} token can be
 * validated and the internal runtime surface stays unreachable. The control-plane audience is optional so an existing
 * Keycloak realm that issues no {@code aud} claim for human clients keeps working until its mappers are deployed.
 */
@ConfigurationProperties(prefix = "aisdlc.security.audience")
public class RuntimeAudienceProperties {
  private String controlPlane = "";
  private String runtime = "";
  private String runtimeAuthorizedParty = "";

  public String getControlPlane() { return controlPlane; } public void setControlPlane(String controlPlane) { this.controlPlane = controlPlane == null ? "" : controlPlane.trim(); }
  public String getRuntime() { return runtime; } public void setRuntime(String runtime) { this.runtime = runtime == null ? "" : runtime.trim(); }
  public String getRuntimeAuthorizedParty() { return runtimeAuthorizedParty; } public void setRuntimeAuthorizedParty(String runtimeAuthorizedParty) { this.runtimeAuthorizedParty = runtimeAuthorizedParty == null ? "" : runtimeAuthorizedParty.trim(); }

  public boolean isControlPlaneAudienceEnforced() { return !controlPlane.isBlank(); }
  public boolean isRuntimeAudienceConfigured() { return !runtime.isBlank(); }
  public boolean isRuntimeAuthorizedPartyEnforced() { return !runtimeAuthorizedParty.isBlank(); }
}
