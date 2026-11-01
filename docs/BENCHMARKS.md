# Local backend benchmark

This opt-in test measures the educational portfolio implementation. Its numbers are **not historical client-production results**, a production capacity estimate, or evidence of a percentage improvement in earlier employment.

## Reproduce

Requires Java 25 and Maven. First run `mvn -B -ntp install` at the repository root to build the production application JARs. Then run the benchmark against dedicated disposable MySQL 8.4 and Kafka infrastructure:

```bash
mvn -B -ntp -Pintegration -pl integration-tests \
  -Dbench.enabled=true -Dit.test=BenchmarkIT \
  '-Dit.mysql.url=jdbc:mysql://127.0.0.1:13306/?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC' \
  -Dit.mysql.user=root -Dit.mysql.password= \
  -Dit.kafka.bootstrap=127.0.0.1:19092 \
  test-compile failsafe:integration-test failsafe:verify
```

The empty password is only for an isolated loopback test instance. Supply the appropriate credentials for your disposable instance. The harness creates unique databases, refuses a broker with existing business topics, and removes only the databases/topics it created. Do not point this workload at a production system.

To also measure Redis, start a dedicated Redis instance on loopback port 16379 and add `-Dbench.redis.enabled=true -Dbench.redis.port=16379`. This instance must have no other clients, since its global hit counter is used for verification. MySQL performance_schema table I/O instrumentation must be enabled to interpret table-fetch counts.

With neither external MySQL nor Kafka properties, the existing harness starts its Testcontainers infrastructure. This alternative needs Docker and is not implied to have been verified by a native-process run. Redis remains a separately provided optional instance.

Results are written to `docs/benchmark-results.json` even when the test fails. Override with `-Dbench.output=/absolute/path/result.json`. A failed or incomplete result is not valid benchmark evidence. The test is skipped unless explicitly enabled.

## Workload and measurements

- Eight application processes: one gateway, one user-service, and two each of data-, scoring-, and report-service. Each has a 192 MiB maximum heap and `ActiveProcessorCount=2`.
- HTTP requests go through the authenticated gateway to the first instance of each service. The workload does not load-balance HTTP requests across both replicas. Both asynchronous consumers and outbox relays run.
- Real MySQL and Kafka; three partitions per business topic, single broker. Synthetic RSA-signed JWTs and JWKS replace the Keycloak login flow.
- Twenty warmup workflows, followed by three rounds of thirty workflows at each concurrency level (1, 5, 10): 270 measured workflows. Round orders are 1/5/10, 5/10/1, and 10/1/5 to reduce simple order bias.
- Each workflow uses a new synthetic customer and UUID event key. Customer creation is outside the timed window. All workflows use the same financial input and verify score 763, the matching report, and a successful PDF response with a PDF header and size greater than 1,000 bytes. This is not exhaustive validation of every PDF's content.
- A fixed thread pool runs a closed-loop workload with no think time. Per-operation timers start when a worker begins, so executor-queue waiting is excluded from individual latency. Whole-phase elapsed time includes executor setup and completion and is used for throughput. Offered load is not a fixed arrival rate.
- Submission latency measures the POST through its 202 response. Score/report visibility is timed from immediately before submission, with 100 ms polling; it includes read requests and is an upper-bound observation of visibility, not pure service processing time. Report polling starts after score visibility.
- PDF latency measures its HTTP request and complete response. Total workflow latency includes submission, visibility checks, and PDF retrieval.
- The integration harness sets outbox polling to **200 ms**. The normal application default is **1,000 ms**; these results must not be presented as default-configuration latency.
- Successful-sample p50/p95 use the nearest-rank method. All failures are recorded separately; a passing run requires zero workload errors and exactly 290 financial, score, and report rows, including warmup.

## Redis comparison

Four score-read phases run in disabled/enabled/enabled/disabled order. The first scoring instance restarts each phase, followed by 200 warmup requests, then 1,000 measured reads at concurrency 10 against twenty fixed customers. These are hot-key reads, not a mixed read/write or cold-cache workload. The enabled phases require exactly 1,000 observed Redis hits each; the disabled phases require zero.

The scoring controller first queries MySQL for the score row, then optionally retrieves the serialized score view from Redis. Consequently a Redis hit **does not eliminate that MySQL lookup**. Table-fetch counters are reported to check this behavior. No database-load reduction or latency improvement is assumed.

Short phases, JIT compilation, gateway pooling, process restarts, background activity, and shared-host scheduling can affect comparisons. The design is not a statistically controlled claim of causal improvement. Keep the four phases visible rather than selecting only a favorable phase.

## Limits

This is a short same-host synthetic benchmark. It excludes browser rendering, public-network latency, Keycloak interactive login, email delivery, Docker deployment, multi-host networking, production data distributions, sustained saturation, failover, and availability/SLA measurement. Maximum tested concurrency is not maximum supported concurrency; observed throughput is not a system capacity guarantee.

See `benchmark-results.json` for raw samples, execution timestamps and failure state. Summarize the completed file with `python3 scripts/summarize-benchmark.py`.
