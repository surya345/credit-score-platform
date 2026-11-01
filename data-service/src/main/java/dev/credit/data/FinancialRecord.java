package dev.credit.data;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "financial_records")
public class FinancialRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(nullable = false)
  public Long id;

  @Column(nullable = false, unique = true)
  public String eventId;

  @Column(nullable = false)
  public String customerId;

  @Column(nullable = false)
  public String officerId;

  @Column(nullable = false)
  public Instant submittedAt;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
  @Column(nullable = false, columnDefinition = "LONGTEXT")
  public String inputJson;

  protected FinancialRecord() {}
}
