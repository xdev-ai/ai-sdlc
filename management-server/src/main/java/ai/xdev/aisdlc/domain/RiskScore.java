package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_scores")
public class RiskScore {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(nullable = false) private int score;
  @Column(name = "risk_band", nullable = false, length = 20) private String riskBand;
  @Column(name = "formula_version", nullable = false, length = 40) private String formulaVersion;
  @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String components;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "source_summary", nullable = false, columnDefinition = "jsonb") private String sourceSummary;
  @Column(name = "calculated_by", nullable = false, length = 200) private String calculatedBy;
  @Column(name = "calculated_at", nullable = false) private Instant calculatedAt = Instant.now();

  protected RiskScore() {}
  public RiskScore(UUID projectId, int score, String riskBand, String formulaVersion, String components, String sourceSummary, String calculatedBy) { this.projectId = projectId; this.score = score; this.riskBand = riskBand; this.formulaVersion = formulaVersion; this.components = components; this.sourceSummary = sourceSummary; this.calculatedBy = calculatedBy; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public int getScore() { return score; } public String getRiskBand() { return riskBand; } public String getFormulaVersion() { return formulaVersion; } public String getComponents() { return components; } public String getSourceSummary() { return sourceSummary; } public String getCalculatedBy() { return calculatedBy; } public Instant getCalculatedAt() { return calculatedAt; }
}
