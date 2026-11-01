# Implementation and interview walkthrough

## Explain the project accurately

Describe this as a portfolio or learning implementation of a credit-analysis workflow. Do not present the sample bank narrative, synthetic data, or educational scoring rules as employment experience or a production banking deployment.

An accurate opening is: “I built an officer-facing credit-analysis application with Angular and Spring Boot. A financial submission is persisted with a transactional outbox, Kafka triggers scoring and reporting, and each result is stored as an immutable snapshot. Keycloak provides OIDC sign-in, and I can explain the delivery guarantees and failure cases.”

## Walk through one request

1. Start with `frontend/src/app.ts`: `submit()` validates the form at the UI, keeps an idempotency key for retries, and receives 202.
2. Open `FinancialController`: it validates JSON and asks user-service to verify the active customer and currency.
3. Open `FinancialService`: the database transaction saves the financial snapshot, outgoing event, and audit row together.
4. Open `OutboxPublisher`: it publishes pending rows after commit and marks them sent after acknowledgement. Explain its crash window and locking cost.
5. Open `FinancialListener` and `ScoringService`: consumer validation, duplicate protection, calculation, persistence, and the second outbox event.
6. Open `ScoringEngine`: explain the documented 763 example and why the model is educational.
7. Open `ReportService` and `PdfRenderer`: immutable report snapshots and PDF generation.
8. Return to Angular: it waits for the matching event and report, then shows explanations and allows authenticated download.

## Questions worth being ready for

**Why Kafka?** It decouples scoring and report generation from the initial HTTP response and permits independent consumers. The tradeoffs are eventual consistency, operational complexity, duplicates, and the need for failure recovery.

**Why an outbox?** A database commit and a Kafka send are two separate side effects. Writing the record and an outbox row in one database transaction prevents losing the event between those operations. It does not eliminate duplicates after a crash.

**Is this exactly once?** No. Delivery is at least once. Idempotent consumer behavior and unique constraints make repeated deliveries safe for the stored business result.

**What if an old event arrives late?** Historical records still persist. Latest queries choose the greatest source version, not arrival time, so an older event does not replace the newer latest result.

**What if Redis is down?** Redis is an optimization for immutable score views. The service catches cache failures and returns the database-backed result. It never relies on cache eviction to remove database rows.

**Why database per service?** It makes ownership explicit and avoids cross-service SQL dependencies. For local cost, the databases share a MySQL process but use separate credentials and schemas. Independent scaling and recovery would need production infrastructure.

**Why Keycloak rather than a homemade login endpoint?** Identity lifecycle and OIDC are delegated to an identity provider. The Angular public client uses PKCE; gateway and services independently validate issuer, audience, signature, expiration, and roles.

**How did you test it?** Point to the actual current results in VALIDATION. Explain the difference between pure algorithm tests, HTTP tests with mocked JWT authentication, persistence tests with H2, real Kafka/Keycloak integration, and a Docker/MySQL deployment. Do not claim a layer was tested when it was only configured.

**What would you improve next?** Choose concrete items: real bureau/model contracts, MySQL concurrency integration tests, DLT operations and alerts, metrics/tracing, outbox pruning/relay performance, per-officer authorization, and infrastructure resilience. Explain the problem each solves.

## Suggested learning order

Start by running the UI and reproducing 763. Read the contracts and algorithm next. Then trace database transactions and Kafka listeners, followed by security and frontend polling. Finally study the failure cases and tests. A useful exercise is to change the sample financial inputs and predict the factor contributions before submitting them.
