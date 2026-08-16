package ai.xdev.aisdlc.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aisdlc.github")
public class GitHubAppProperties {
  private boolean enabled;
  private String apiBaseUrl = "https://api.github.com";
  private String appId;
  private String privateKeyPem;
  private String webhookSecret;
  private String checkName = "AI-SDLC Governance";
  private String detailsUrlTemplate;
  private Duration requestTimeout = Duration.ofSeconds(10);
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getApiBaseUrl() { return apiBaseUrl; }
  public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
  public String getAppId() { return appId; }
  public void setAppId(String appId) { this.appId = appId; }
  public String getPrivateKeyPem() { return privateKeyPem; }
  public void setPrivateKeyPem(String privateKeyPem) { this.privateKeyPem = privateKeyPem; }
  public String getWebhookSecret() { return webhookSecret; }
  public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
  public String getCheckName() { return checkName; }
  public void setCheckName(String checkName) { this.checkName = checkName; }
  public String getDetailsUrlTemplate() { return detailsUrlTemplate; }
  public void setDetailsUrlTemplate(String detailsUrlTemplate) { this.detailsUrlTemplate = detailsUrlTemplate; }
  public Duration getRequestTimeout() { return requestTimeout; }
  public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
  public boolean isWebhookConfigured() { return webhookSecret != null && !webhookSecret.isBlank(); }
  public boolean isAppConfigured() { return enabled && appId != null && !appId.isBlank() && privateKeyPem != null && !privateKeyPem.isBlank(); }
}
