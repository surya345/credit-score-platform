# Credit Score Tool — measured local backend results

**Scope: educational portfolio project, tested locally. These are not historical client-production metrics.**

Run: 2026-10-17T21:37:58.949902481Z to 2026-10-17T21:40:34.013962038Z (UTC).

## End-to-end workflows

270 measured workflows, plus 20 warmup workflows. Every measured workflow submitted financial data, verified score 763 and its matching report, and retrieved a PDF. Zero observed workload errors.

The table pools 90 measured samples per concurrency level across three rounds. Latencies are in milliseconds. Visibility includes 100 ms polling; outbox polling was configured to 200 ms.

| Concurrent workflows | Samples | Submit p50 / p95 | Score visible p50 / p95 | Report visible p50 / p95 | PDF p50 / p95 | Full flow p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 90 | 36.0 / 70.0 | 177.0 / 300.9 | 289.0 / 423.7 | 32.2 / 54.3 | 327.0 / 466.1 |
| 5 | 90 | 56.1 / 149.5 | 269.7 / 424.8 | 386.8 / 552.3 | 47.4 / 224.2 | 438.1 / 643.3 |
| 10 | 90 | 95.9 / 296.4 | 327.4 / 997.1 | 472.6 / 1165.2 | 86.5 / 210.9 | 565.8 / 1405.6 |

Observed throughput includes score/report polling and PDF retrieval; customer creation is excluded. This is a closed-loop workload, not a maximum-capacity test.

| Concurrent workflows | Completed workflows/second across three rounds |
|---|---|
| 1 | 2.49, 4.41, 2.81 |
| 5 | 8.56, 10.95, 13.48 |
| 10 | 12.81, 9.77, 19.25 |

## Redis hot-read comparison

Four phases in disabled/enabled/enabled/disabled order; 200 warmup and 1,000 measured reads per phase, concurrency 10, twenty fixed customers. All requests went through the authenticated gateway.

| Phase | Cache | Reads | p50 ms | p95 ms | Reads/s | Redis hits | MySQL score-table fetches |
|---|---|---:|---:|---:|---:|---:|---:|
| score-read-1 | Off | 1000 | 16.00 | 29.27 | 574.5 | 0 | 1000 |
| score-read-2 | On | 1000 | 19.45 | 34.49 | 479.3 | 1000 | 1000 |
| score-read-3 | On | 1000 | 17.51 | 29.96 | 533.5 | 1000 | 1000 |
| score-read-4 | Off | 1000 | 17.53 | 28.80 | 542.9 | 0 | 1000 |

The controller still reads MySQL before consulting Redis. Cache hits alone do not demonstrate a reduction in database lookups. These short phases are affected by JIT and shared-host variation; do not turn their differences into a claimed production improvement percentage.

## Test setup and checks

- Java runtime: 25.0.3+9-LTS; OS: Linux amd64.
- Reported processors: 8; cgroup CPU quota/period: `800000 100000`; memory limit: `8589934592` bytes.
- Infrastructure versions recorded for this execution: MySQL 8.4.11, Kafka 4.4.0, Redis 8.2.0. Eight application JVMs; 192 MiB maximum heap and two active processors configured per app JVM.
- Spring Boot 4.1.1 application JARs; separate service databases; three Kafka partitions per business topic; two scoring and two report consumers.
- The local test issuer signs real RSA JWTs and serves JWKS. Keycloak login and Angular are outside the measurement.
- Final database totals: creditScores=290, creditReports=290, financialRecords=290.
- Total recorded workload errors: 0; test-resource cleanup: passed.

## Resume wording

Use under a clearly identified personal/portfolio project:

> Benchmarked a Java/Spring Boot credit-scoring portfolio application using MySQL, Kafka and Redis, completing 270 measured synthetic workflows at concurrency levels of 1, 5 and 10 with zero observed workflow errors in the local test.

Do not attach these 2026 local results to earlier client employment. Do not claim a latency improvement percentage, production throughput, uptime, or database-load reduction from this test.

## Interpretation limits

Same-host, short, closed-loop synthetic workload with 200 ms outbox polling (normal application default: 1,000 ms). Visibility timings include 100 ms polling. Customer creation is outside the timed interval; HTTP requests route to one instance per service while both asynchronous consumers run. No public-network, browser, email, Docker, sustained-saturation, failover or availability test was performed. Maximum tested concurrency is not maximum supported concurrency.

The included `benchmark-results.json` contains individual observations and phase summaries. `BENCHMARKS.md` documents reproduction and method. Percentiles use nearest rank on successful samples; all errors are separately reported.
