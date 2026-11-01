# Validation record

Latest verification: **17 October 2026**. Earlier frontend and live-browser results below were recorded on 16 October 2026.

## Local backend benchmark — 17 October UTC / 18 October India time

A fresh Java 25.0.3 build passed all 23 existing backend tests. The opt-in `BenchmarkIT` then passed using native MySQL 8.4.11, Kafka 4.4.0 and Redis 8.2.0: 20 warmup workflows, 270 measured workflows (90 each at concurrency 1, 5 and 10), and 4,000 measured hot score reads across cache-off/on/on/off phases. There were zero observed workload errors. Final counts were 290 financial records, 290 scores and 290 reports. Test-owned databases/topics and native servers were cleaned up.

At concurrency 10, pooled full-workflow p95 was 1,405.6 ms, including asynchronous visibility polling and PDF retrieval. This configuration uses 200 ms outbox polling rather than the normal 1,000 ms default. Real Redis hits were verified, but each 1,000-read phase still produced 1,000 MySQL score-table fetches; no database-lookup reduction or latency-improvement claim is supported. These short same-host measurements do not establish production capacity or historical client performance.

See [Benchmark report](Benchmark_Report.md), [method and reproduction](BENCHMARKS.md), [raw measurements](benchmark-results.json), and [sanitized JUnit summary](benchmark-test-verification.json). The existing nine concurrency/recovery integration cases were not rerun in this benchmark task; their earlier verification is recorded below.

## MySQL/Kafka concurrency and recovery — 17 October 2026

`mvn -B -ntp -Pintegration verify` with the documented external-infrastructure properties completed successfully across the nine-project reactor. **32 tests passed: 23 existing backend tests plus nine integration tests; zero failures, errors, or skips.** The integration class took 122.4 seconds; the complete reactor took 2 minutes 40 seconds.

This execution used real **MySQL 8.4.11** and **Kafka 4.4.0** as native local processes, Zulu OpenJDK **25.0.4.1**, and Maven **3.9.16**. Each business service ran its production JAR against a separate MySQL database, with Flyway migrations and Hibernate schema validation. Eight Java processes ran: user-service, gateway, and two instances each of data-service, scoring-service, and report-service. The test issuer signed real RSA JWTs and served JWKS; Keycloak and Redis were not part of this run.

| Integration check | Verified behavior |
|---|---|
| Concurrent identical requests | 12 simultaneous requests across two data instances leave one financial record, outbox entry, and audit entry; retries resolve to the original event; score 763 and its matching PDF are available. |
| Conflicting payloads | Two competing payloads sharing one key cannot overwrite the winner; retrying the other payload returns 409. |
| Distinct requests | 12 simultaneous submissions survive competing outbox publishers and produce 12 distinct scores and reports; the greatest source version remains latest. |
| Duplicate Kafka events | 18 financial replays and 18 report replays across three partitions and two consumers leave one score/report and no duplicate-related dead letters; committed offsets confirm the replays were drained. |
| Out-of-order events | A later-arriving older source version cannot replace the latest score. |
| Consumer process crashes | Hard-stopped scoring/report processes recover their Kafka backlog after restart. |
| Pending outbox recovery | An unreachable publisher bootstrap endpoint leaves the event pending in MySQL; after a hard publisher stop and healthy restart, the event is delivered once at the stored score/report level. |
| Invalid event | An unsupported event schema reaches the dead-letter topic while the following valid event on that partition is processed. |
| Transaction rollback | A MySQL trigger deliberately fails audit insertion; the financial record, outbox entry, and audit entry all roll back, and the same request succeeds after removing the trigger. |

The unavailable-broker check changes the publisher's bootstrap endpoint; it does not crash or fail over the Kafka broker. These checks demonstrate the tested cases, not a load-capacity bound or exactly-once Kafka delivery. The default Testcontainers path has the same test assertions but was not executed here.

The new `scripts/verify-compose.py` builds and verifies an isolated complete Docker stack, including Nginx, Keycloak service-account authentication, real Redis hits, PDF, and local Mailpit delivery. Python compilation passed, and both the normal and generated Compose configurations passed the official Compose JSON schema. Invoking it here correctly returned a failure explaining that Docker was unavailable; **no container runtime success is claimed**. The Jenkins pipeline now runs both verification paths and publishes JUnit results, but Jenkins itself was not run.

Reproduction commands and limits are in [INTEGRATION](INTEGRATION.md). Sanitized per-test names, timings, and outcomes from the successful JUnit reports are in [verification-results.json](verification-results.json).

## Automated backend checks

`mvn -B -ntp verify` completed successfully on 16 October across the full eight-project Maven reactor (root, two shared libraries, four business services, and gateway). All 23 tests also passed in the 17 October integration-profile run described above.

| Test class | Tests | Coverage |
|---|---:|---|
| CustomerApiTest | 6 | Anonymous access, role restriction, create/search, invalid email, duplicate reference, administrator audit |
| FinancialApiTest | 5 | Idempotent retry, conflicting retry, zero income, fractional count, missing customer |
| ScoringEngineTest | 6 | Example 763, maximum 850, minimum 300, history cap, monotonic debt and missed-payment behavior |
| ScoringPersistenceTest | 3 | Duplicate delivery, out-of-order source versions, unsupported event schema |
| ReportTest | 2 | Duplicate delivery and one-page PDF content |
| GatewayContextTest | 1 | Full gateway startup and registration of all eight routes |
| **Total** | **23** | **0 failures, 0 errors** |

These unit/API tests use Spring Security's test JWT support. Their persistence tests run Flyway migrations and Hibernate schema validation against H2 in MySQL compatibility mode. MySQL locking and multi-instance behavior are covered separately by the new integration cases above.

After the final PDF font fix, both report tests and report-service packaging were rerun successfully. The generated PDF was rendered and visually inspected with embedded DejaVu fonts. The font files and their redistribution notice are included under report-service resources.

## Frontend

- `npm ci` dependency resolution is recorded by the supplied lockfile; the initial installation used `npm install` to create that lockfile.
- `npm run build` passed, including Angular strict template and TypeScript checks. It was rerun after the final small visual fixes.
- Production initial bundle: approximately **280 kB raw / 75 kB estimated transfer**.
- Desktop and mobile screenshots were reviewed. The 390-pixel mobile viewport had no document-level horizontal overflow.

## Live integration

The five packaged application JARs were started with real **Kafka 4.4.0** and **Keycloak 26.8.1**, plus the Angular development server. Each service used its own H2 database in place of MySQL for this execution. The JDK was Zulu OpenJDK 25.0.4.1. The Docker build selects Temurin 25.

The API smoke test verified:

1. Anonymous requests return 401, and an officer cannot read administrator audit endpoints.
2. A synthetic customer can be created through the gateway.
3. Invalid income is rejected before creating a financial record/event.
4. Repeated identical submissions with one UUID key return the same event and persist one financial record; changing the payload returns 409.
5. Real Kafka delivery produces score **763** and exactly one matching report for that submission.
6. Latest/history endpoints return the expected source event, score, and report relationship.
7. An authenticated PDF can be downloaded.
8. The actual Java SMTP client sends a report summary containing 763 to a local test SMTP receiver. No message was relayed externally.
9. Score reads succeed with caching enabled while Redis is unavailable, exercising the database fallback.

The Chromium/Playwright browser smoke test verified:

1. Interactive officer sign-in through Keycloak's authorization-code/PKCE flow.
2. Customer selection and sample financial submission through Angular.
3. The completion message and displayed score 763 after asynchronous processing.
4. Downloading the authenticated PDF from the Reports tab.
5. Mobile layout without horizontal document overflow and no unhandled browser page errors.

These full integration checks passed before the final font-embedding and plus-sign rendering fixes. Those isolated changes were then checked by report tests/PDF visual inspection and a fresh Angular production build, respectively.

## Not executed here

- Docker was unavailable, so **Docker image builds, Testcontainers startup, and the complete Compose deployment were not executed**. Native MySQL/Kafka integration did pass. Compose configuration/schema checks do not exclude container-specific problems.
- The Mailpit container, Jenkins execution, production load, broker/database failover, disaster recovery, and an operator-driven dead-letter replay workflow were not exercised. Real Redis hits were subsequently verified in the local benchmark above; that does not validate the Compose deployment. Multi-instance request/event races, service process recovery, and invalid-event dead-letter routing passed as described above.
- The browser test used the Angular development server; the production Angular bundle compiled successfully but Nginx was not run here.

Run `python scripts/verify-compose.py` on a Docker-enabled machine for the isolated complete-stack check. Alternatively, start the normal demo with `docker compose up --build -d` and run `python scripts/smoke-test.py --email`. These checks create only synthetic demo records. This project is a local educational implementation, not a certified production banking system.
