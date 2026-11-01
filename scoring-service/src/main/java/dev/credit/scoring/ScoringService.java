package dev.credit.scoring;

import dev.credit.contracts.*;
import dev.credit.platform.*;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoringService {
  private final ScoreRepository scores;
  private final ScoringEngine engine;
  private final OutboxRepository outbox;
  private final JsonCodec json;
  private final AuditRepository audit;
  private final Validator validator;

  public ScoringService(
      ScoreRepository s,
      ScoringEngine e,
      OutboxRepository o,
      JsonCodec j,
      AuditRepository a,
      Validator v) {
    scores = s;
    engine = e;
    outbox = o;
    json = j;
    audit = a;
    validator = v;
  }

  @Transactional
  public void process(FinancialDataEvent event) {
    if (event.schemaVersion() != 1
        || event.input() == null
        || event.sourceVersion() < 1
        || event.submittedAt() == null
        || event.officerId() == null) throw new IllegalArgumentException("Invalid event envelope");
    UUID.fromString(event.eventId());
    UUID.fromString(event.customerId());
    if (!validator.validate(event.input()).isEmpty())
      throw new IllegalArgumentException("Invalid financial inputs");
    if (scores.existsBySourceEventId(event.eventId())) return;
    var result = engine.calculate(event.input());
    var s = new CreditScore();
    s.id = UUID.randomUUID().toString();
    s.sourceEventId = event.eventId();
    s.customerId = event.customerId();
    s.sourceVersion = event.sourceVersion();
    s.calculatedAt = Instant.now();
    s.score = result.score();
    s.riskBand = result.riskBand();
    s.resultJson = json.write(result);
    scores.saveAndFlush(s);
    var update =
        new ScoreCalculatedEvent(
            s.id, 1, s.id, s.sourceEventId, s.customerId, s.sourceVersion, s.calculatedAt, result);
    outbox.save(new OutboxEntry(Topics.SCORE_UPDATES, s.customerId, json.write(update)));
    audit.save(new AuditEntry("scoring-service", "SCORE_CALCULATED", s.id));
  }
}
