package ai.xdev.aisdlc.evidence;

import ai.xdev.aisdlc.domain.DomainTypes.ObjectLockMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Provider-neutral boundary for immutable evidence bytes. No web, JPA, or AWS type crosses this interface. */
public interface ObjectStoragePort {
  record Upload(String key, String contentType, byte[] bytes, String sha256Digest, Map<String, String> metadata) {}
  record StoredObject(String bucket, String key, long sizeBytes) {}
  StoredObject store(Upload upload);
  URI generatePresignedGetUrl(String bucket, String key, Duration ttl);
  void applyRetentionLock(String bucket, String key, ObjectLockMode mode, Instant retentionUntil);
  void delete(String bucket, String key);
}
