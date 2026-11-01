package dev.credit.reports;

import dev.credit.contracts.*;
import dev.credit.platform.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
  public record ReportView(
      String id,
      String customerId,
      String scoreId,
      long sourceVersion,
      Instant generatedAt,
      ScoreResult result) {}

  private final ReportRepository reports;
  private final JsonCodec json;
  private final AuditRepository audit;

  public ReportService(ReportRepository r, JsonCodec j, AuditRepository a) {
    reports = r;
    json = j;
    audit = a;
  }

  @Transactional
  public void process(ScoreCalculatedEvent e) {
    if (e.schemaVersion() != 1
        || e.result() == null
        || e.sourceVersion() < 1
        || e.calculatedAt() == null) throw new IllegalArgumentException("Invalid score event");
    UUID.fromString(e.eventId());
    UUID.fromString(e.scoreId());
    UUID.fromString(e.customerId());
    if (e.result().score() < 300 || e.result().score() > 850)
      throw new IllegalArgumentException("Invalid score");
    if (reports.existsByEventId(e.eventId())) return;
    var r = new CreditReport();
    r.id = UUID.randomUUID().toString();
    r.scoreId = e.scoreId();
    r.eventId = e.eventId();
    r.customerId = e.customerId();
    r.sourceVersion = e.sourceVersion();
    r.generatedAt = Instant.now();
    r.resultJson = json.write(e.result());
    reports.saveAndFlush(r);
    audit.save(new AuditEntry("report-service", "REPORT_GENERATED", r.id));
  }

  public CreditReport get(String id) {
    return reports.findById(id).orElseThrow(() -> new ApiException(404, "Report not found."));
  }

  public ReportView view(CreditReport r) {
    return new ReportView(
        r.id,
        r.customerId,
        r.scoreId,
        r.sourceVersion,
        r.generatedAt,
        json.read(r.resultJson, ScoreResult.class));
  }
}
