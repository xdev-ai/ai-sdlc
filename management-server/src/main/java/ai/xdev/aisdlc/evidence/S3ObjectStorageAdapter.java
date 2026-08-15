package ai.xdev.aisdlc.evidence;

import ai.xdev.aisdlc.domain.DomainTypes.ObjectLockMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
public class S3ObjectStorageAdapter implements ObjectStoragePort, AutoCloseable {
  private final EvidenceStorageProperties properties;
  private final S3Client client;
  private final S3Presigner presigner;

  public S3ObjectStorageAdapter(EvidenceStorageProperties properties) {
    this.properties = properties;
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
    var configuration = S3Configuration.builder().pathStyleAccessEnabled(properties.isForcePathStyle()).build();
    this.client = S3Client.builder().endpointOverride(URI.create(properties.getEndpoint())).region(Region.of(properties.getRegion())).credentialsProvider(credentials).serviceConfiguration(configuration).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    this.presigner = S3Presigner.builder().endpointOverride(URI.create(properties.getEndpoint())).region(Region.of(properties.getRegion())).credentialsProvider(credentials).serviceConfiguration(configuration).build();
  }

  @Override public StoredObject store(Upload upload) {
    client.putObject(PutObjectRequest.builder().bucket(properties.getBucket()).key(upload.key()).contentType(upload.contentType()).metadata(upload.metadata()).build(), RequestBody.fromBytes(upload.bytes()));
    return new StoredObject(properties.getBucket(), upload.key(), upload.bytes().length);
  }

  @Override public URI generatePresignedGetUrl(String bucket, String key, Duration ttl) {
    var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
    return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(request).build()).url().toString());
  }

  @Override public void applyRetentionLock(String bucket, String key, ObjectLockMode mode, Instant retentionUntil) {
    ObjectLockRetentionMode sdkMode = mode == ObjectLockMode.COMPLIANCE ? ObjectLockRetentionMode.COMPLIANCE : ObjectLockRetentionMode.GOVERNANCE;
    client.putObjectRetention(PutObjectRetentionRequest.builder().bucket(bucket).key(key).retention(ObjectLockRetention.builder().mode(sdkMode).retainUntilDate(retentionUntil).build()).build());
  }

  @Override public void delete(String bucket, String key) { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()); }
  @Override public void close() { presigner.close(); client.close(); }
}
