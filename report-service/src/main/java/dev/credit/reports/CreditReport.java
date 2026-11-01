package dev.credit.reports;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "credit_reports")
public class CreditReport {
  @Id
  @Column(nullable = false)
  public String id;

  @Column(nullable = false, unique = true)
  public String scoreId;

  @Column(nullable = false, unique = true)
  public String eventId;

  @Column(nullable = false)
  public String customerId;

  @Column(nullable = false)
  public long sourceVersion;

  @Column(nullable = false)
  public Instant generatedAt;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
  @Column(nullable = false, columnDefinition = "LONGTEXT")
  public String resultJson;

  protected CreditReport() {}
}
