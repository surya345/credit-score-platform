package dev.credit.data;

import dev.credit.contracts.*;
import dev.credit.platform.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialService {
  private final FinancialRepository records;
  private final OutboxRepository outbox;
  private final AuditRepository audit;
  private final JsonCodec json;

  public FinancialService(
      FinancialRepository r, OutboxRepository o, AuditRepository a, JsonCodec j) {
    records = r;
    outbox = o;
    audit = a;
    json = j;
  }

  @Transactional
  public FinancialDataEvent submit(
      String customer, FinancialInput input, String actor, String key) {
    String eventId = UUID.fromString(key).toString();
    var existing = records.findByEventId(eventId);
    if (existing.isPresent()) {
      var e = view(existing.get());
      if (!e.customerId().equals(customer)
          || !e.officerId().equals(actor)
          || !e.input().equals(input))
        throw new ApiException(409, "Idempotency-Key was used for another request.");
      return e;
    }
    var r = new FinancialRecord();
    r.customerId = customer;
    r.eventId = eventId;
    r.officerId = actor;
    r.submittedAt = Instant.now();
    r.inputJson = json.write(input);
    records.saveAndFlush(r);
    var e = view(r);
    outbox.save(new OutboxEntry(Topics.FINANCIAL_DATA, customer, json.write(e)));
    audit.save(new AuditEntry(actor, "FINANCIAL_DATA_SUBMITTED", eventId));
    return e;
  }

  public FinancialDataEvent view(FinancialRecord r) {
    return new FinancialDataEvent(
        r.eventId,
        1,
        r.customerId,
        r.id,
        r.submittedAt,
        json.read(r.inputJson, FinancialInput.class),
        r.officerId);
  }
}
