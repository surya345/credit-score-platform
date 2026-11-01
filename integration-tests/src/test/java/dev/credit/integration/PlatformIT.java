package dev.credit.integration;

import static dev.credit.integration.TestPlatform.*;
import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PlatformIT {
  private static TestPlatform p;
  @BeforeAll static void start() throws Exception {
    p = new TestPlatform();
    try { p.start(); }
    catch (Exception | Error e) {
      try { p.close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
      p = null; throw e;
    }
  }
  @AfterAll static void stop() throws Exception { if (p != null) p.close(); }

  @Test @Order(1)
  void concurrentIdenticalSubmissionsPersistOneRecordOutboxAndAudit() throws Exception {
    String customer = p.customer(), key = UUID.randomUUID().toString();
    var results = concurrent(12, i -> () -> p.request("data-" + (i % 2 + 1), "POST",
        "/api/data/customers/" + customer, SAMPLE, key));
    assertThat(results).anyMatch(r -> r.statusCode() == 202);
    for (var r : results) assertThat(r.statusCode()).as(body(r)).isIn(202, 409);
    // Unique-key collisions may return 409; retrying the original key must resolve them.
    var first = p.submit(customer, key);
    for (int i = 0; i < 4; i++) assertThat(p.submit(customer, key)).isEqualTo(first);
    assertThat(p.count("data", "SELECT COUNT(*) FROM financial_records WHERE event_id=?", key)).isEqualTo(1);
    assertThat(p.count("data", "SELECT COUNT(*) FROM outbox_entries WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.eventId'))=?", key)).isEqualTo(1);
    assertThat(p.count("data", "SELECT COUNT(*) FROM audit_entries WHERE resource_id=?", key)).isEqualTo(1);
    p.awaitResults(customer, 1);
    var score = p.api("GET", "/api/scores/customers/" + customer + "/latest", null, null, 200);
    assertThat(score.path("sourceEventId").asText()).isEqualTo(key);
    assertThat(score.path("result").path("score").asInt()).isEqualTo(763);
    var report = p.api("GET", "/api/reports/customers/" + customer, null, null, 200).path("items").get(0);
    assertThat(report.path("scoreId").asText()).isEqualTo(score.path("id").asText());
    var pdf = p.request("gateway-1", "GET", "/api/reports/" + report.path("id").asText() + "/pdf", null, null);
    assertThat(pdf.statusCode()).isEqualTo(200);
    assertThat(pdf.body().length).isGreaterThan(1000);
    assertThat(new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
  }

  @Test @Order(2)
  void conflictingPayloadsCannotOverwriteTheWinningSubmission() throws Exception {
    String customer = p.customer(), key = UUID.randomUUID().toString();
    var changed = new HashMap<>(SAMPLE); changed.put("monthlyIncome", 90000);
    var results = concurrent(12, i -> () -> p.request("data-" + (i % 2 + 1), "POST",
        "/api/data/customers/" + customer, i % 2 == 0 ? SAMPLE : changed, key));
    for (var r : results) assertThat(r.statusCode()).as(body(r)).isIn(202, 409);
    var winner = results.stream().filter(r -> r.statusCode() == 202).findFirst().orElseThrow();
    int income = JSON.readTree(winner.body()).path("input").path("monthlyIncome").asInt();
    p.api("POST", "/api/data/customers/" + customer, income == 80000 ? SAMPLE : changed, key, 202);
    p.api("POST", "/api/data/customers/" + customer, income == 80000 ? changed : SAMPLE, key, 409);
    assertThat(p.count("data", "SELECT COUNT(*) FROM financial_records WHERE event_id=?", key)).isEqualTo(1);
    assertThat(p.count("data", "SELECT JSON_EXTRACT(input_json,'$.monthlyIncome') FROM financial_records WHERE event_id=?", key)).isEqualTo(income);
    p.awaitResults(customer, 1);
  }

  @Test @Order(3)
  void distinctSubmissionsSurviveCompetingOutboxPublishers() throws Exception {
    String customer = p.customer();
    var results = concurrent(12, i -> () -> p.request("data-" + (i % 2 + 1), "POST",
        "/api/data/customers/" + customer, SAMPLE, UUID.randomUUID().toString()));
    long greatestVersion = 0;
    for (var r : results) {
      assertThat(r.statusCode()).as(body(r)).isEqualTo(202);
      greatestVersion = Math.max(greatestVersion, JSON.readTree(r.body()).path("sourceVersion").asLong());
    }
    p.awaitResults(customer, 12);
    assertThat(p.count("data", "SELECT COUNT(*) FROM financial_records WHERE customer_id=?", customer)).isEqualTo(12);
    assertThat(p.count("scoring", "SELECT COUNT(DISTINCT source_event_id) FROM credit_scores WHERE customer_id=?", customer)).isEqualTo(12);
    assertThat(p.api("GET", "/api/scores/customers/" + customer + "/latest", null, null, 200)
        .path("sourceVersion").asLong()).isEqualTo(greatestVersion);
  }

  @Test @Order(4)
  void duplicateKafkaEventsAcrossConsumersCreateOneScoreAndReport() throws Exception {
    String customer = p.customer(), id = UUID.randomUUID().toString();
    var event = p.event(customer, id, 10001, 1);
    try (var scoreDlt = p.tail(FINANCIAL + ".DLT"); var reportDlt = p.tail(SCORES + ".DLT")) {
      // Cross-partition injection deliberately stresses cross-instance duplicate races.
      p.awaitConsumed("scoring", concurrent(18, i -> () -> p.publish(FINANCIAL, i % 3, customer, event)));
      p.awaitResults(customer, 1);
      String scoreId = p.text("scoring", "SELECT id FROM credit_scores WHERE source_event_id=?", id);
      var reportEvent = JSON.readTree(p.text("scoring", "SELECT payload FROM outbox_entries WHERE message_key=?", customer));
      p.awaitConsumed("report", concurrent(18, i -> () -> p.publish(SCORES, i % 3, customer, reportEvent)));
      assertThat(p.count("scoring", "SELECT COUNT(*) FROM credit_scores WHERE source_event_id=?", id)).isEqualTo(1);
      assertThat(p.count("scoring", "SELECT COUNT(*) FROM outbox_entries WHERE message_key=?", customer)).isEqualTo(1);
      assertThat(p.count("report", "SELECT COUNT(*) FROM credit_reports WHERE score_id=?", scoreId)).isEqualTo(1);
      assertThat(scoreDlt.poll(Duration.ofMillis(500)).count()).isZero();
      assertThat(reportDlt.poll(Duration.ofMillis(500)).count()).isZero();
    }
  }

  @Test @Order(5)
  void lateOlderEventCannotReplaceLatestScore() throws Exception {
    String customer = p.customer(), newer = UUID.randomUUID().toString(), older = UUID.randomUUID().toString();
    p.publish(FINANCIAL, 0, customer, p.event(customer, newer, 200, 1)); p.awaitResults(customer, 1);
    p.publish(FINANCIAL, 0, customer, p.event(customer, older, 100, 1)); p.awaitResults(customer, 2);
    var latest = p.api("GET", "/api/scores/customers/" + customer + "/latest", null, null, 200);
    assertThat(latest.path("sourceEventId").asText()).isEqualTo(newer);
    assertThat(latest.path("sourceVersion").asLong()).isEqualTo(200);
  }

  @Test @Order(6)
  void killedConsumersProcessBacklogAfterRestart() throws Exception {
    String customer = p.customer(), key = UUID.randomUUID().toString();
    var consumers = List.of("scoring-1", "scoring-2", "report-1", "report-2");
    try {
      for (String name : consumers) p.stopService(name);
      p.submit(customer, key);
      await("outbox published while consumers stopped", Duration.ofSeconds(30), () ->
          p.count("data", "SELECT COUNT(*) FROM outbox_entries WHERE message_key=? AND published_at IS NOT NULL", customer) == 1);
      assertThat(p.count("scoring", "SELECT COUNT(*) FROM credit_scores WHERE customer_id=?", customer)).isZero();
      p.restart("scoring-1"); p.restart("scoring-2");
      await("score committed while report consumers stopped", Duration.ofSeconds(60), () ->
          p.count("scoring", "SELECT COUNT(*) FROM credit_scores WHERE customer_id=?", customer) == 1);
      assertThat(p.count("report", "SELECT COUNT(*) FROM credit_reports WHERE customer_id=?", customer)).isZero();
      p.restart("report-1"); p.restart("report-2"); p.awaitResults(customer, 1);
    } finally {
      for (String name : consumers) if (!p.processes.get(name).isAlive()) p.restart(name);
    }
  }

  @Test @Order(7)
  void unpublishedOutboxSurvivesPublisherCrashAndRecovers() throws Exception {
    String customer = p.customer(), key = UUID.randomUUID().toString();
    try {
      p.stopService("data-1"); p.stopService("data-2");
      p.startService("data-1", Map.of("spring.kafka.bootstrap-servers", "127.0.0.1:" + freePort(),
          "spring.kafka.producer.properties.max.block.ms", "1000", "spring.kafka.producer.properties.request.timeout.ms", "1000",
          "spring.kafka.producer.properties.delivery.timeout.ms", "3000"));
      p.awaitHealthy("data-1"); p.submit(customer, key);
      await("failed send retained in MySQL", Duration.ofSeconds(30), () ->
          p.count("data", "SELECT COUNT(*) FROM outbox_entries WHERE message_key=? AND published_at IS NULL AND attempts>0", customer) == 1);
      assertThat(p.count("scoring", "SELECT COUNT(*) FROM credit_scores WHERE customer_id=?", customer)).isZero();
      p.stopService("data-1");
      assertThat(p.count("data", "SELECT COUNT(*) FROM financial_records WHERE event_id=?", key)).isEqualTo(1);
      assertThat(p.count("data", "SELECT COUNT(*) FROM outbox_entries WHERE message_key=? AND published_at IS NULL", customer)).isEqualTo(1);
    } finally { p.restart("data-1"); p.restart("data-2"); }
    p.awaitResults(customer, 1);
    assertThat(p.count("data", "SELECT COUNT(*) FROM outbox_entries WHERE message_key=? AND published_at IS NOT NULL", customer)).isEqualTo(1);
    assertThat(p.submit(customer, key).path("eventId").asText()).isEqualTo(key);
  }

  @Test @Order(8)
  void invalidEventReachesDeadLetterTopicWithoutBlockingValidWork() throws Exception {
    String customer = p.customer(), invalid = UUID.randomUUID().toString();
    try (var dlt = p.tail(FINANCIAL + ".DLT")) {
      p.publish(FINANCIAL, 1, customer, p.event(customer, invalid, 500, 999));
      var good = p.publish(FINANCIAL, 1, customer, p.event(customer, UUID.randomUUID().toString(), 501, 1));
      p.awaitConsumed("scoring", List.of(good)); p.awaitResults(customer, 1);
      var dead = new ArrayList<JsonNode>();
      await("invalid event in DLT", Duration.ofSeconds(30), () -> {
        dlt.poll(Duration.ofMillis(300)).forEach(r -> dead.add(JSON.readTree(r.value())));
        return dead.stream().anyMatch(e -> e.path("eventId").asText().equals(invalid));
      });
      assertThat(p.count("scoring", "SELECT COUNT(*) FROM credit_scores WHERE source_event_id=?", invalid)).isZero();
    }
  }

  @Test @Order(9)
  void auditFailureRollsBackFinancialRecordAndOutboxTogether() throws Exception {
    String customer = p.customer(), key = UUID.randomUUID().toString();
    try (var c = p.connection("data"); var s = c.createStatement()) {
      s.execute("CREATE TRIGGER integration_audit_failure BEFORE INSERT ON audit_entries FOR EACH ROW "
          + "BEGIN IF NEW.resource_id = '" + key + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Injected integration failure'; END IF; END");
      try {
        assertThat(p.request("gateway-1", "POST", "/api/data/customers/" + customer, SAMPLE, key).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(p.count("data", "SELECT COUNT(*) FROM financial_records WHERE event_id=?", key)).isZero();
        assertThat(p.count("data", "SELECT COUNT(*) FROM outbox_entries WHERE message_key=?", customer)).isZero();
        assertThat(p.count("data", "SELECT COUNT(*) FROM audit_entries WHERE resource_id=?", key)).isZero();
      } finally { s.execute("DROP TRIGGER integration_audit_failure"); }
    }
    p.submit(customer, key); p.awaitResults(customer, 1);
  }

  private static String body(HttpResponse<byte[]> r) { return new String(r.body(), StandardCharsets.UTF_8); }
  private static <T> List<T> concurrent(int count, IntFunction<Callable<T>> task) throws Exception {
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new CountDownLatch(count); var gate = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        var work = task.apply(i);
        futures.add(workers.submit(() -> { ready.countDown(); gate.await(); return work.call(); }));
      }
      if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Workers did not reach start gate");
      gate.countDown(); List<T> results = new ArrayList<>();
      for (var f : futures) results.add(f.get(40, TimeUnit.SECONDS)); return results;
    }
  }
}
