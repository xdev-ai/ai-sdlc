package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "validation_runs", uniqueConstraints = @UniqueConstraint(name = "validation_project_idempotency_uq", columnNames = {"project_id", "idempotency_key"}))
public class ValidationRun {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "idempotency_key", nullable = false, length = 120) private String idempotencyKey;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private DomainTypes.ValidationStatus status;
  @Column(nullable = false, length = 120) private String cliVersion;
  @Column(nullable = false, length = 160) private String kitVersion;
  @Column(nullable = false, length = 160) private String modelPin;
  @Column(nullable = false, length = 120) private String actorSubject;
  @Column(nullable = false) private Instant completedAt = Instant.now();
  protected ValidationRun() {}
  public ValidationRun(UUID projectId, String idempotencyKey, DomainTypes.ValidationStatus status, String cliVersion, String kitVersion, String modelPin, String actorSubject) { this.projectId = projectId; this.idempotencyKey = idempotencyKey; this.status = status; this.cliVersion = cliVersion; this.kitVersion = kitVersion; this.modelPin = modelPin; this.actorSubject = actorSubject; }
  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public DomainTypes.ValidationStatus getStatus() { return status; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getCliVersion() { return cliVersion; }
  public String getKitVersion() { return kitVersion; }
  public String getModelPin() { return modelPin; }
  public String getActorSubject() { return actorSubject; }
  public Instant getCompletedAt() { return completedAt; }
}
