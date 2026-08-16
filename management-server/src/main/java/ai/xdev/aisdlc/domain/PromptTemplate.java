package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prompt_templates", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "template_key", "semantic_version"}))
public class PromptTemplate {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "template_key", nullable = false, length = 160) private String templateKey;
  @Column(name = "semantic_version", nullable = false, length = 80) private String semanticVersion;
  @Column(name = "display_name", nullable = false, length = 240) private String displayName;
  @Column(name = "source_reference", length = 2000) private String sourceReference;
  @Column(name = "template_sha256", nullable = false, length = 64) private String templateSha256;
  @Column(nullable = false, length = 80) private String classification;
  @Column(name = "registered_by", nullable = false, length = 200) private String registeredBy;
  @Column(name = "registered_at", nullable = false) private Instant registeredAt = Instant.now();
  protected PromptTemplate() {}
  public PromptTemplate(UUID projectId, String templateKey, String semanticVersion, String displayName, String sourceReference, String templateSha256, String classification, String registeredBy) { this.projectId = projectId; this.templateKey = templateKey; this.semanticVersion = semanticVersion; this.displayName = displayName; this.sourceReference = sourceReference; this.templateSha256 = templateSha256; this.classification = classification; this.registeredBy = registeredBy; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public String getTemplateKey() { return templateKey; } public String getSemanticVersion() { return semanticVersion; } public String getDisplayName() { return displayName; } public String getSourceReference() { return sourceReference; } public String getTemplateSha256() { return templateSha256; } public String getClassification() { return classification; } public String getRegisteredBy() { return registeredBy; } public Instant getRegisteredAt() { return registeredAt; }
}
