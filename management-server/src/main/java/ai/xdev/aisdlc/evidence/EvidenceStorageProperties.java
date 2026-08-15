package ai.xdev.aisdlc.evidence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aisdlc.evidence-storage")
public class EvidenceStorageProperties {
  private String endpoint = "http://localhost:9000";
  private String region = "us-east-1";
  private String bucket = "aisdlc-evidence";
  private String accessKey = "aisdlc-minio";
  private String secretKey = "aisdlc-minio-change-me";
  private boolean forcePathStyle = true;
  private Duration presignTtl = Duration.ofMinutes(5);
  private long maxUploadBytes = 26_214_400L;
  public String getEndpoint() { return endpoint; } public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
  public String getRegion() { return region; } public void setRegion(String region) { this.region = region; }
  public String getBucket() { return bucket; } public void setBucket(String bucket) { this.bucket = bucket; }
  public String getAccessKey() { return accessKey; } public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
  public String getSecretKey() { return secretKey; } public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
  public boolean isForcePathStyle() { return forcePathStyle; } public void setForcePathStyle(boolean forcePathStyle) { this.forcePathStyle = forcePathStyle; }
  public Duration getPresignTtl() { return presignTtl; } public void setPresignTtl(Duration presignTtl) { this.presignTtl = presignTtl; }
  public long getMaxUploadBytes() { return maxUploadBytes; } public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
}
