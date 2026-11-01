package dev.credit.platform;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_entries")
public class OutboxEntry {
  @Id public String id;

  @Column(nullable = false)
  public String topic;

  @Column(nullable = false)
  public String messageKey;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
  @Column(nullable = false, columnDefinition = "LONGTEXT")
  public String payload;

  @Column(nullable = false)
  public Instant createdAt;

  public Instant publishedAt;

  @Column(nullable = false)
  public int attempts;

  protected OutboxEntry() {}

  public OutboxEntry(String topic, String key, String payload) {
    id = UUID.randomUUID().toString();
    this.topic = topic;
    messageKey = key;
    this.payload = payload;
    createdAt = Instant.now();
  }
}
