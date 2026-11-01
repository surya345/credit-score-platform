package dev.credit.users;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {
  @Id
  @Column(nullable = false)
  public String id;

  @Column(nullable = false, unique = true)
  public String reference;

  @Column(nullable = false)
  public String fullName;

  @Column(nullable = false)
  public String email;

  @Column(nullable = false)
  public String currency;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public Instant createdAt;

  protected Customer() {}
}
