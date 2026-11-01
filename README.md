# CreditLens — Credit Score Analysis

A complete local portfolio application built from the credit-analysis project brief. A credit officer signs in, selects or creates a customer, submits a financial snapshot, and receives an explainable score and a PDF report through an asynchronous Kafka workflow.

**This is an educational model, not a bureau score or a lending recommendation. Use synthetic data.** The project is not affiliated with a bank and does not claim to reproduce a bank's proprietary scoring system.

## Start the complete application

Install Docker Desktop with Docker Compose v2 and start Docker. Allocate about **8 GB RAM** to Docker for this multi-service demo. Internet access is needed for the first image and dependency downloads. You do not need Java, Maven, or Node installed for the Docker route.

Extract this folder, open a terminal in it, and run:

```sh
docker compose up --build -d
docker compose ps
```

The first build can take several minutes. Open **http://localhost:4200** after startup. If the sign-in service is still starting, wait briefly and refresh. Use `localhost` consistently because the demo's OIDC redirects are configured for that hostname.

| Role | Username | Password |
|---|---|---|
| Credit officer | `officer` | `Officer123!` |
| Application administrator | `admin` | `Admin123!` |

1. Click **Sign in to workspace** and enter the officer credentials.
2. Select **Aarav Sharma**, then **Financial data**.
3. Keep the sample values and click **Save & analyze**.
4. The overview shows **763 / 850**, the five factor contributions, and model observations.
5. Open **Reports** to download the PDF. **Email summary** sends to the local Mailpit inbox at http://localhost:8025.
6. Sign in as the application administrator to see the **Audit** tab.

All credentials, service-account secrets, unencrypted listeners, and single-node settings are for this local demo only. Services bind to the local host in Compose. This configuration is not a production deployment.

## Stack

Versions were selected and resolved during implementation in October 2026. Exact versions are pinned where practical; the npm lockfile records the full frontend dependency tree.

| Component | Version / choice |
|---|---|
| Java | **25 LTS** |
| Spring Boot | **4.1.1** |
| Spring Cloud | **2025.1.3** |
| Apache Kafka broker | **4.4.0**, KRaft |
| Spring Kafka / Kafka client | Boot-managed **4.1.1 / 4.2.1** |
| Angular | **22.2.3**; CLI/build **22.2.2** |
| TypeScript | **6.0.x**, lockfile pinned |
| Node build image | **24.19.0** |
| MySQL | **8.4.11 LTS** |
| Redis | **8.2** |
| Identity | **Keycloak 26.8.1**, OIDC authorization code + PKCE |
| PDF | Apache PDFBox **3.0.6** |
| Logging | Log4j2, structured ECS JSON |

The Kafka **broker** is 4.4.0. The Java client remains at the version supported by the Spring Boot dependency management rather than overriding the framework's tested dependency set. The broker image is built from Apache's release archive with a pinned SHA-512 checksum.

## What is included

- Angular workspace with customer search, pagination, customer creation, financial forms, score explanations, analysis history, report downloads, and administrator audit views.
- API gateway and four Spring Boot business services, each with an independent database and database user.
- Server-side validation, role checks, issuer/audience validation, UUID idempotency keys, transactional outboxes, duplicate-event protection, and dead-letter topics.
- Versioned, immutable score and report snapshots; newer source versions remain latest even when older events arrive late.
- Redis caching of immutable score snapshots, with database fallback if Redis is unavailable.
- Flyway migrations, Dockerfiles, Compose infrastructure, a Jenkins pipeline, backend tests, and executable API/browser smoke tests.
- Local SMTP capture through Mailpit; no real email provider or bureau integration is required.

## Repository map

| Directory | Responsibility |
|---|---|
| `contracts` | Financial input validation and versioned event contracts |
| `service-support` | Security, error responses, audit, JSON, Kafka, and outbox infrastructure |
| `user-service` | Customer profiles and current-user information |
| `data-service` | Financial submissions, customer checks, idempotency, financial-data events |
| `scoring-service` | Educational scoring algorithm, score persistence, Redis reads |
| `report-service` | Report snapshots, PDF rendering, local email summaries |
| `gateway` | Authenticated routing and administrator audit routes |
| `frontend` | Angular application |
| `infra` | Kafka, MySQL, Keycloak, and shared Java Docker build |
| `integration-tests` | MySQL/Kafka concurrency, duplicate delivery, crash recovery, and rollback checks |
| `scripts` | Start helpers, smoke tests, and isolated Compose verification |
| `docs` | Architecture, APIs, setup, walkthrough, and validation |

Read [SETUP](docs/SETUP.md), [ARCHITECTURE](docs/ARCHITECTURE.md), [API](docs/API.md), [WALKTHROUGH](docs/WALKTHROUGH.md), and [VALIDATION](docs/VALIDATION.md).

For reproducible local latency and Redis hot-read measurements, see [BENCHMARKS](docs/BENCHMARKS.md). Benchmark results describe this educational portfolio implementation, not historical client-production performance.

## Build and verify from source

For local compilation install **JDK 25**, **Maven 3.9+**, and **Node 24.19+ within the Node 24 line**.

```sh
mvn -B -ntp verify
cd frontend
npm ci
npm run build
```

With the complete stack running, from the project root:

```sh
python scripts/smoke-test.py
```

This creates a synthetic customer and verifies access rules, validation, idempotency, Kafka-driven scoring, matching reports, and PDF download. Add `--email` to test local Mailpit delivery. The smoke client has the officer role only. Disable/remove it before adapting this configuration for another environment.

For the dedicated MySQL/Kafka integration suite and an isolated full-stack verification, use:

```sh
mvn -B -ntp -Pintegration verify
python scripts/verify-compose.py
```

Both commands require Docker by default. The first launches packaged Java services and exercises concurrent requests, duplicate events, process restarts, pending outbox recovery, and transaction rollback. The second builds the Compose images and checks the API through Nginx, Keycloak service-account tokens, real Redis hits, PDF download, and delivery to local Mailpit. Each owns disposable test resources; neither deletes the normal demo's volumes. See [INTEGRATION](docs/INTEGRATION.md) for requirements, execution evidence, and the external MySQL/Kafka option.

## Stop and restart

```sh
docker compose down
docker compose up -d
```

Named volumes retain demo databases and Kafka data. `docker compose logs -f gateway data-service scoring-service report-service` helps diagnose startup or processing failures. See SETUP for a deliberately destructive clean reset.

## Scope

This implements the core officer-to-report workflow. A real bureau integration, a validated risk model, batch jobs, customer self-service, multi-tenancy, production HA, Kubernetes manifests, Splunk ingestion, and production deployment are outside this local version. The architecture document explains the guarantees and tradeoffs without claiming exactly-once delivery or production readiness.
