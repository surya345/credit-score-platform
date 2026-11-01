# Integration verification

Verified on 17 October 2026: **23 existing backend tests and all nine new integration tests passed**, with zero failures, errors, or skips. The integration run used native MySQL 8.4.11 and Kafka 4.4.0, eight packaged application processes, JDK 25.0.4.1, and Maven 3.9.16. Docker was unavailable, so the Testcontainers route and complete Compose execution remain unverified here. See [VALIDATION](VALIDATION.md) and the machine-readable [test results](verification-results.json).

Run from the project root with JDK 25, Maven 3.9+ and a running Docker engine:

```sh
mvn -B -ntp -Pintegration verify
```

The profile runs the existing unit tests, builds the production JARs, then runs nine Failsafe integration tests. Testcontainers starts disposable MySQL 8.4.11 and Kafka 4.4.0 instances. Eight application processes exercise two instances each of the data, scoring and report services, plus user-service and the gateway. Missing Docker is a failure, not a skipped test.

The suite covers concurrent identical/conflicting/distinct submissions, duplicate events across Kafka partitions and consumers, out-of-order source versions, hard consumer process stops and backlog recovery, pending outbox recovery after an unavailable broker endpoint and publisher crash, dead-letter handling, and atomic rollback when audit insertion fails. It also checks score 763 and the matching authenticated PDF.

A local RSA/JWKS issuer supplies signed test JWTs. This suite does not test Keycloak browser login, Angular/Nginx, SMTP, Redis cache hits, broker replication, database failover, or load capacity. The unavailable-broker case changes a publisher's bootstrap endpoint; it does not shut down the real broker.

On hosts without Docker, supply an already-running dedicated MySQL and Kafka instance:

```sh
mvn -B -ntp -Pintegration verify \
  '-Dit.mysql.url=jdbc:mysql://127.0.0.1:13306/?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC' \
  -Dit.mysql.user=root -Dit.mysql.password= \
  -Dit.kafka.bootstrap=127.0.0.1:19092
```

The empty password and unencrypted JDBC example are only for a disposable loopback test server. Do not use a shared or production broker/database. The suite refuses a broker containing the business topics. It creates uniquely named databases, owns only its newly created topics, and removes those resources at teardown. The supplied database account needs schema creation/removal and trigger privileges. Test application processes use that account within the test databases; this is not a production credential layout.

Reports: `integration-tests/target/failsafe-reports`. Application logs: `integration-tests/target/platform-logs/<run-id>`. Failure reports must be inspected before claiming success.

## Complete Compose verification

With Docker Compose v2 and Python 3.10+ installed, run from the project root:

```sh
python scripts/verify-compose.py
```

This command builds the supplied images and starts an isolated copy of the complete stack. It uses a unique project name, its own named volumes, random loopback ports, and matching Keycloak/frontend configuration. No normal demo containers or volumes are stopped or deleted. The test project and its volumes are removed on success or failure; use `--keep` only to retain that test project for debugging. The printed artifacts directory contains the generated configuration and `compose.log`.

Checks cover:

- MySQL initialization and migrations through the packaged applications.
- Keycloak service-account authentication and API access rules.
- The production Nginx API proxy, validation, idempotency, Kafka scoring/reporting, and authenticated PDF download.
- Real Redis score entries and cache hits.
- The Java mail client delivering one synthetic report containing score 763 to local Mailpit.

This does not automate interactive browser login or render the Angular screens. The existing `scripts/browser-smoke.cjs` checks that flow separately against the normal demo endpoints. It also does not test production HA, database or broker failover, security hardening, or sustained load.

The supplied Jenkins pipeline runs the integration profile, frontend build, and this full-stack verifier. It archives JUnit results and test logs. Neither Docker-dependent check silently skips when Docker is absent. Allow roughly 8 GB of memory, internet access for initial downloads, and several minutes for builds and restarts. Jenkins and the container-backed paths have not been executed in this workspace.
