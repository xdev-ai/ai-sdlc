package ai.xdev.aisdlc.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aisdlc.notifications")
public class NotificationProperties {
  private String encryptionKey;
  private String fromAddress;
  private int batchSize = 25;
  private int maxAttempts = 5;
  private Duration retryBaseDelay = Duration.ofMinutes(1);
  private Duration reminderLeadTime = Duration.ofHours(24);
  private Duration reminderInterval = Duration.ofHours(6);
  private String dispatchCron = "0 */2 * * * *";
  private String approvalSlaCron = "30 */5 * * * *";
  public String getEncryptionKey() { return encryptionKey; } public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
  public String getFromAddress() { return fromAddress; } public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
  public int getBatchSize() { return batchSize; } public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public int getMaxAttempts() { return maxAttempts; } public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
  public Duration getRetryBaseDelay() { return retryBaseDelay; } public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
  public Duration getReminderLeadTime() { return reminderLeadTime; } public void setReminderLeadTime(Duration reminderLeadTime) { this.reminderLeadTime = reminderLeadTime; }
  public Duration getReminderInterval() { return reminderInterval; } public void setReminderInterval(Duration reminderInterval) { this.reminderInterval = reminderInterval; }
  public String getDispatchCron() { return dispatchCron; } public void setDispatchCron(String dispatchCron) { this.dispatchCron = dispatchCron; }
  public String getApprovalSlaCron() { return approvalSlaCron; } public void setApprovalSlaCron(String approvalSlaCron) { this.approvalSlaCron = approvalSlaCron; }
  public boolean isConfigured() { return encryptionKey != null && encryptionKey.matches("[A-Za-z0-9_-]{43}"); }
}
