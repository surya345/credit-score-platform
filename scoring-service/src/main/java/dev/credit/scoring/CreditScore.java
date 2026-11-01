package dev.credit.scoring;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "credit_scores")
public class CreditScore {
  @Id
  @Column(nullable = false)
  public String id;

  @Column(nullable = false, unique = true)
  public String sourceEventId;

  @Column(nullable = false)
  public String customerId;

  @Column(nullable = false)
  public long sourceVersion;

  @Column(nullable = false)
  public Instant calculatedAt;

  @Column(nullable = false)
  public int score;

  @Column(nullable = false)
  public String riskBand;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
  @Column(nullable = false, columnDefinition = "LONGTEXT")
  public String resultJson;

  protected CreditScore() {}
}
