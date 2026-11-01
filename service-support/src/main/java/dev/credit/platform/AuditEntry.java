package dev.credit.platform;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_entries")
public class AuditEntry {
  @Id public String id;

  @Column(nullable = false)
  public String actorId;

  @Column(nullable = false)
  public String action;

  @Column(nullable = false)
  public String resourceId;

  @Column(nullable = false)
  public Instant occurredAt;

  protected AuditEntry() {}

  public AuditEntry(String actor, String action, String resource) {
    id = UUID.randomUUID().toString();
    actorId = actor;
    this.action = action;
    resourceId = resource;
    occurredAt = Instant.now();
  }
}
