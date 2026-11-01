package dev.credit.integration;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Production JARs, MySQL and Kafka. A local issuer supplies signed test JWTs. */
final class TestPlatform implements AutoCloseable {
  static final String FINANCIAL = "financial-data-collected", SCORES = "credit-score-updates";
  static final List<String> TOPICS = List.of(FINANCIAL, SCORES, FINANCIAL + ".DLT", SCORES + ".DLT");
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Map<String, Object> SAMPLE = Map.of("monthlyIncome", 80000,
      "monthlyDebtPayments", 12000, "creditUtilizationPercent", 25, "missedPaymentsLast12Months", 0,
      "creditHistoryMonths", 72, "recentCreditInquiries", 1, "currency", "INR");
  final String run = "creditit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  final Path root = Path.of(System.getProperty("it.project-root")).toAbsolutePath();
  final Path logs = root.resolve("integration-tests/target/platform-logs/" + run);
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  final Map<String, String> databases = new LinkedHashMap<>();
  final Map<String, Integer> ports = new LinkedHashMap<>();
  final Map<String, Process> processes = new LinkedHashMap<>();
  private final List<String> createdDatabases = new ArrayList<>();
  private MySQLContainer mysql;
  private KafkaContainer kafka;
  private HttpServer issuer;
  private String issuerUrl, token, jdbc, dbUser, dbPassword, bootstrap;
  private Admin admin;
  private KafkaProducer<String, String> producer;
  private boolean ownsTopics;

  void start() throws Exception {
    Files.createDirectories(logs);
    String externalJdbc = System.getProperty("it.mysql.url"), externalKafka = System.getProperty("it.kafka.bootstrap");
    if ((externalJdbc == null) != (externalKafka == null))
      throw new IllegalArgumentException("Supply both it.mysql.url and it.kafka.bootstrap, or neither.");
    if (externalJdbc == null) {
      // Unavailable Docker fails the suite. Integration tests are never silently skipped.
      mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("bootstrap")
          .withUsername("suite").withPassword(UUID.randomUUID().toString()).withEnv("MYSQL_ROOT_HOST", "%");
      kafka = new KafkaContainer("apache/kafka:4.4.0");
      mysql.start(); kafka.start();
      jdbc = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
          + "/?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC";
      dbUser = "root"; dbPassword = mysql.getPassword(); bootstrap = kafka.getBootstrapServers();
    } else {
      jdbc = externalJdbc; bootstrap = externalKafka;
      dbUser = System.getProperty("it.mysql.user", "root"); dbPassword = System.getProperty("it.mysql.password", "");
    }
    admin = Admin.create(Map.of("bootstrap.servers", bootstrap, "default.api.timeout.ms", "15000",
        "request.timeout.ms", "5000"));
    var existing = admin.listTopics().names().get(20, TimeUnit.SECONDS);
    if (TOPICS.stream().anyMatch(existing::contains))
      throw new IllegalStateException("Use a dedicated broker without business topics. Existing topics are not deleted.");
    admin.createTopics(TOPICS.stream().map(t -> new NewTopic(t, 3, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
    ownsTopics = true;
    producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrap, "acks", "all", "enable.idempotence", "true",
        "delivery.timeout.ms", "15000", "request.timeout.ms", "5000"), new StringSerializer(), new StringSerializer());
    try (var c = databaseConnection(); var s = c.createStatement()) {
      for (String service : List.of("user", "data", "scoring", "report")) {
        String database = run + "_" + service;
        s.executeUpdate("CREATE DATABASE `" + database + "`");
        createdDatabases.add(database); databases.put(service, database);
      }
      try (var v = s.executeQuery("SELECT VERSION()")) { v.next(); System.out.println("Real MySQL: " + v.getString(1)); }
    }
    startIssuer();
    for (String name : List.of("user-1", "data-1", "data-2", "scoring-1", "scoring-2", "report-1", "report-2", "gateway-1")) {
      int port; do { port = freePort(); } while (ports.containsValue(port)); ports.put(name, port);
    }
    // Complete migrations on one instance before starting replicas.
    var primary = List.of("user-1", "data-1", "scoring-1", "report-1", "gateway-1");
    for (String name : primary) startService(name, Map.of());
    for (String name : primary) awaitHealthy(name);
    for (String name : List.of("data-2", "scoring-2", "report-2")) startService(name, Map.of());
    for (String name : List.of("data-2", "scoring-2", "report-2")) awaitHealthy(name);
    for (String service : List.of("scoring", "report")) {
      await("two " + service + " consumers", Duration.ofSeconds(60), () -> {
        try { return admin.describeConsumerGroups(List.of(group(service))).all().get(10, TimeUnit.SECONDS)
            .get(group(service)).members().size() == 2; }
        catch (Exception e) { throw new RuntimeException(e); }
      });
    }
    System.out.println("Started 8 packaged application processes. Logs: " + logs);
  }

  private void startIssuer() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID(run).generate();
    issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuerUrl = "http://127.0.0.1:" + issuer.getAddress().getPort();
    byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    issuer.createContext("/jwks", e -> {
      e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, keys.length);
      try (var out = e.getResponseBody()) { out.write(keys); }
    });
    issuer.start();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(run).build(),
        new JWTClaimsSet.Builder().issuer(issuerUrl).subject("integration-officer").audience("credit-api")
            .issueTime(new java.util.Date()).expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
            .claim("realm_access", Map.of("roles", List.of("OFFICER"))).build());
    jwt.sign(new RSASSASigner(key)); token = jwt.serialize();
  }

  void startService(String name, Map<String, String> overrides) throws IOException {
    if (processes.containsKey(name) && processes.get(name).isAlive()) throw new IllegalStateException("Already running: " + name);
    String service = name.substring(0, name.lastIndexOf('-'));
    String module = service.equals("gateway") ? "gateway" : service + "-service";
    Path jar = root.resolve(module + "/target/" + module + "-1.0.0.jar");
    if (!Files.isRegularFile(jar)) throw new IOException("Run the Maven reactor from its root: missing " + jar);
    var args = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString(),
        "-Xms48m", "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar", jar.toString()));
    Map<String, String> config = new LinkedHashMap<>();
    config.put("server.address", "127.0.0.1"); config.put("server.port", ports.get(name).toString());
    config.put("app.security.issuer", issuerUrl); config.put("app.security.jwk-set-uri", issuerUrl + "/jwks");
    config.put("app.users-url", url("user-1")); config.put("app.data-url", url("data-1"));
    config.put("app.scores-url", url("scoring-1")); config.put("app.reports-url", url("report-1"));
    config.put("app.demo-seed", "false"); config.put("app.cache.enabled", "false");
    config.put("spring.kafka.bootstrap-servers", bootstrap); config.put("spring.kafka.consumer.group-id", group(service));
    config.put("spring.kafka.admin.auto-create", "false"); config.put("app.events.publish-delay-ms", "200");
    config.put("spring.datasource.hikari.maximum-pool-size", "8"); config.put("spring.datasource.hikari.minimum-idle", "1");
    if (!service.equals("gateway")) config.put("spring.datasource.url", databaseUrl(service));
    config.putAll(overrides); config.forEach((k, v) -> args.add("--" + k + "=" + v));
    var b = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(name + ".log").toFile()));
    b.environment().put("DB_USER", dbUser); b.environment().put("DB_PASSWORD", dbPassword);
    processes.put(name, b.start());
  }
  void awaitHealthy(String name) throws Exception {
    await(name + " readiness", Duration.ofSeconds(100), () -> {
      if (!processes.get(name).isAlive()) throw new IllegalStateException(name + " exited; inspect " + logs.resolve(name + ".log"));
      try { return http.send(HttpRequest.newBuilder(URI.create(url(name) + "/actuator/health/readiness"))
          .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
      catch (IOException | InterruptedException e) { return false; }
    });
  }
  void stopService(String name) throws Exception {
    var p = processes.get(name); if (p == null || !p.isAlive()) return;
    p.destroyForcibly(); if (!p.waitFor(15, TimeUnit.SECONDS)) throw new IllegalStateException("Cannot stop " + name);
  }
  void restart(String name) throws Exception { stopService(name); startService(name, Map.of()); awaitHealthy(name); }
  String url(String name) { return "http://127.0.0.1:" + ports.get(name); }
  String group(String service) { return run + "_" + service; }

  HttpResponse<byte[]> request(String name, String method, String path, Object body, String key) throws Exception {
    var r = HttpRequest.newBuilder(URI.create(url(name) + path)).timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + token);
    if (key != null) r.header("Idempotency-Key", key);
    if (body != null) r.header("Content-Type", "application/json");
    r.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
  }
  JsonNode api(String method, String path, Object body, String key, int expected) throws Exception {
    var r = request("gateway-1", method, path, body, key);
    if (r.statusCode() != expected) throw new AssertionError(method + " " + path + ": expected " + expected + ", got "
        + r.statusCode() + " " + new String(r.body(), StandardCharsets.UTF_8));
    return JSON.readTree(r.body());
  }
  String customer() throws Exception {
    return api("POST", "/api/customers", Map.of("reference", "IT-" + UUID.randomUUID(), "fullName", "Synthetic Integration Customer",
        "email", "integration@example.test", "currency", "INR"), null, 201).path("id").asText();
  }
  JsonNode submit(String customer, String key) throws Exception { return api("POST", "/api/data/customers/" + customer, SAMPLE, key, 202); }
  Map<String, Object> event(String customer, String id, long version, int schema) {
    return Map.of("eventId", id, "schemaVersion", schema, "customerId", customer, "sourceVersion", version,
        "submittedAt", Instant.now().toString(), "input", SAMPLE, "officerId", "integration-officer");
  }
  RecordMetadata publish(String topic, int partition, String key, Object body) throws Exception {
    return producer.send(new ProducerRecord<>(topic, partition, key, JSON.writeValueAsString(body))).get(20, TimeUnit.SECONDS);
  }
  void awaitConsumed(String service, Collection<RecordMetadata> records) throws Exception {
    Map<TopicPartition, Long> expected = new HashMap<>();
    for (var r : records) expected.merge(new TopicPartition(r.topic(), r.partition()), r.offset() + 1, Math::max);
    await(service + " committed replayed records", Duration.ofSeconds(60), () -> {
      try {
        var offsets = admin.listConsumerGroupOffsets(group(service)).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        return expected.entrySet().stream().allMatch(e -> offsets.get(e.getKey()) != null && offsets.get(e.getKey()).offset() >= e.getValue());
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }
  KafkaConsumer<String, String> tail(String topic) {
    var c = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", bootstrap, "group.id", run + "_probe_" + UUID.randomUUID(),
        "enable.auto.commit", "false", "auto.offset.reset", "earliest"), new StringDeserializer(), new StringDeserializer());
    var ps = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1), new TopicPartition(topic, 2));
    c.assign(ps); c.seekToEnd(ps); ps.forEach(c::position); return c;
  }
  String databaseUrl(String service) {
    int q = jdbc.indexOf('?'); String base = q < 0 ? jdbc : jdbc.substring(0, q);
    base = base.substring(0, base.indexOf('/', "jdbc:mysql://".length()) + 1);
    return base + databases.get(service) + (q < 0 ? "?connectionTimeZone=UTC" : jdbc.substring(q));
  }
  Connection databaseConnection() throws SQLException { return DriverManager.getConnection(jdbc, dbUser, dbPassword); }
  Connection connection(String service) throws SQLException { return DriverManager.getConnection(databaseUrl(service), dbUser, dbPassword); }
  long count(String service, String sql, String... values) {
    try (var c = connection(service); var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setString(i + 1, values[i]);
      try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
    } catch (SQLException e) { throw new RuntimeException(e); }
  }
  String text(String service, String sql, String value) throws Exception {
    try (var c = connection(service); var s = c.prepareStatement(sql)) {
      s.setString(1, value);
      try (var r = s.executeQuery()) { if (!r.next()) throw new AssertionError("Missing query result"); return r.getString(1); }
    }
  }
  void awaitResults(String customer, int expected) throws Exception {
    await(expected + " scores/reports for " + customer, Duration.ofSeconds(75), () ->
        count("scoring", "SELECT COUNT(*) FROM credit_scores WHERE customer_id=?", customer) == expected
        && count("report", "SELECT COUNT(*) FROM credit_reports WHERE customer_id=?", customer) == expected);
  }
  static void await(String description, Duration timeout, BooleanSupplier check) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) { if (check.getAsBoolean()) return; Thread.sleep(200); }
    throw new AssertionError("Timed out waiting for " + description);
  }
  static int freePort() throws IOException {
    try (var s = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); }
  }
  @Override public void close() throws Exception {
    List<Exception> failures = new ArrayList<>();
    for (String name : processes.keySet()) { try { stopService(name); } catch (Exception e) { failures.add(e); } }
    if (issuer != null) issuer.stop(0);
    if (producer != null) producer.close(Duration.ofSeconds(5));
    if (admin != null) {
      if (ownsTopics) { try { admin.deleteTopics(TOPICS).all().get(20, TimeUnit.SECONDS); } catch (Exception e) { failures.add(e); } }
      admin.close(Duration.ofSeconds(5));
    }
    if (!createdDatabases.isEmpty()) {
      try (var c = databaseConnection(); var s = c.createStatement()) {
        for (String db : createdDatabases) s.executeUpdate("DROP DATABASE `" + db + "`");
      } catch (Exception e) { failures.add(e); }
    }
    if (kafka != null) kafka.close(); if (mysql != null) mysql.close();
    if (!failures.isEmpty()) { var e = new Exception("Cleanup failed for test resources " + run); failures.forEach(e::addSuppressed); throw e; }
  }
}
