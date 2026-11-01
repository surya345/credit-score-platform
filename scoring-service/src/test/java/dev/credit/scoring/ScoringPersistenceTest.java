package dev.credit.scoring;

import static org.assertj.core.api.Assertions.*;

import dev.credit.contracts.*;
import dev.credit.platform.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ScoringPersistenceTest {
  @Autowired ScoringService service;
  @Autowired ScoreRepository scores;
  @Autowired OutboxRepository outbox;
  @Autowired AuditRepository audit;
  String customer = UUID.randomUUID().toString();

  @BeforeEach
  void reset() {
    scores.deleteAll();
    outbox.deleteAll();
    audit.deleteAll();
  }

  private FinancialDataEvent event(long version, int schema) {
    return new FinancialDataEvent(
        UUID.randomUUID().toString(),
        schema,
        customer,
        version,
        Instant.now(),
        new FinancialInput(
            new BigDecimal("80000"),
            new BigDecimal("12000"),
            new BigDecimal("25"),
            0,
            72,
            1,
            "INR"),
        "officer");
  }

  @Test
  void duplicateDeliveryCreatesOneScoreAndOneEvent() {
    var e = event(1, 1);
    service.process(e);
    service.process(e);
    assertThat(scores.count()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  void lateOlderEventCannotReplaceLatest() {
    service.process(event(20, 1));
    service.process(event(10, 1));
    assertThat(
            scores
                .findFirstByCustomerIdOrderBySourceVersionDesc(customer)
                .orElseThrow()
                .sourceVersion)
        .isEqualTo(20);
    assertThat(scores.count()).isEqualTo(2);
  }

  @Test
  void unsupportedSchemaHasNoSideEffects() {
    assertThatThrownBy(() -> service.process(event(1, 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(scores.count()).isZero();
    assertThat(outbox.count()).isZero();
  }
}
