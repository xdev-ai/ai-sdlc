package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(nullable = false, unique = true, length = 80) private String slug;
  @Column(nullable = false, length = 160) private String name;
  @Column(nullable = false) private Instant createdAt = Instant.now();
  protected Organization() {}
  public Organization(String slug, String name) { this.slug = slug; this.name = name; }
  public UUID getId() { return id; }
  public String getSlug() { return slug; }
  public String getName() { return name; }
}

