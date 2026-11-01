#!/usr/bin/env python3
"""Reproduce a readable report from BenchmarkIT's raw evidence; standard library only."""
import json
import math
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1]
source = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'docs/benchmark-results.json'
output = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else root / 'docs/Benchmark_Report.md'
data = json.loads(source.read_text())
if data.get('status') != 'passed' or data.get('totalErrors') != 0 or data.get('cleanup') != 'passed':
    raise SystemExit('Refusing a success report: benchmark did not finish with zero errors and successful cleanup.')

def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]

lines = ['# Credit Score Tool — measured local backend results', '',
    '**Scope: educational portfolio project, tested locally. These are not historical client-production metrics.**', '',
    f"Run: {data['startedAtUtc']} to {data['finishedAtUtc']} (UTC).", '',
    '## End-to-end workflows', '',
    '270 measured workflows, plus 20 warmup workflows. Every measured workflow submitted financial data, verified score 763 and its matching report, and retrieved a PDF. Zero observed workload errors.', '',
    'The table pools 90 measured samples per concurrency level across three rounds. Latencies are in milliseconds. Visibility includes 100 ms polling; outbox polling was configured to 200 ms.', '',
    '| Concurrent workflows | Samples | Submit p50 / p95 | Score visible p50 / p95 | Report visible p50 / p95 | PDF p50 / p95 | Full flow p50 / p95 |',
    '|---|---:|---:|---:|---:|---:|---:|']

groups = {}
for c in (1, 5, 10):
    phases = [p for p in data['phases'] if p['name'].startswith('workflow-round-') and p['concurrency'] == c]
    samples = [s for p in phases for s in p['samples'] if s['ok']]
    assert len(samples) == 90
    groups[c] = (phases, samples)
    row = [str(c), str(len(samples))]
    for metric in ('submissionMs', 'scoreVisibleMs', 'reportVisibleMs', 'pdfDownloadMs', 'totalWorkflowMs'):
        vals = [s[metric] for s in samples]
        row.append(f'{percentile(vals,.5):.1f} / {percentile(vals,.95):.1f}')
    lines.append('| ' + ' | '.join(row) + ' |')

lines += ['', 'Observed throughput includes score/report polling and PDF retrieval; customer creation is excluded. This is a closed-loop workload, not a maximum-capacity test.', '',
    '| Concurrent workflows | Completed workflows/second across three rounds |', '|---|---|']
for c, (phases, samples) in groups.items():
    lines.append(f"| {c} | " + ', '.join(f"{p['successfulOperationsPerSecond']:.2f}" for p in phases) + ' |')

lines += ['', '## Redis hot-read comparison', '',
    'Four phases in disabled/enabled/enabled/disabled order; 200 warmup and 1,000 measured reads per phase, concurrency 10, twenty fixed customers. All requests went through the authenticated gateway.', '',
    '| Phase | Cache | Reads | p50 ms | p95 ms | Reads/s | Redis hits | MySQL score-table fetches |', '|---|---|---:|---:|---:|---:|---:|---:|']
reads = [p for p in data['phases'] if p['name'].startswith('score-read-')]
for p in reads:
    stats = p['summaryMs']['latencyMs']
    lines.append(f"| {p['name']} | {'On' if p['cacheEnabled'] else 'Off'} | {p['requests']} | {stats['p50']:.2f} | {stats['p95']:.2f} | {p['successfulOperationsPerSecond']:.1f} | {p['redisHitDelta']} | {p['mysqlScoreTableFetchDelta']} |")
lines += ['', 'The controller still reads MySQL before consulting Redis. Cache hits alone do not demonstrate a reduction in database lookups. These short phases are affected by JIT and shared-host variation; do not turn their differences into a claimed production improvement percentage.', '',
    '## Test setup and checks', '',
    f"- Java runtime: {data['java']}; OS: {data['os']}.",
    f"- Reported processors: {data['availableProcessors']}; cgroup CPU quota/period: `{data['cpuQuota']}`; memory limit: `{data['memoryLimitBytes']}` bytes.",
    '- Infrastructure versions recorded for this execution: ' + ', '.join(f'{k} {v}' for k,v in data.get('infrastructureVersions', {}).items()) + '. Eight application JVMs; 192 MiB maximum heap and two active processors configured per app JVM.',
    '- Spring Boot 4.1.1 application JARs; separate service databases; three Kafka partitions per business topic; two scoring and two report consumers.',
    '- The local test issuer signs real RSA JWTs and serves JWKS. Keycloak login and Angular are outside the measurement.',
    '- Final database totals: ' + ', '.join(f'{k}={v}' for k,v in data['finalRows'].items()) + '.',
    f"- Total recorded workload errors: {data['totalErrors']}; test-resource cleanup: {data['cleanup']}.",
    '', '## Resume wording', '',
    'Use under a clearly identified personal/portfolio project:', '',
    '> Benchmarked a Java/Spring Boot credit-scoring portfolio application using MySQL, Kafka and Redis, completing 270 measured synthetic workflows at concurrency levels of 1, 5 and 10 with zero observed workflow errors in the local test.', '',
    'Do not attach these 2026 local results to earlier client employment. Do not claim a latency improvement percentage, production throughput, uptime, or database-load reduction from this test.', '',
    '## Interpretation limits', '',
    'Same-host, short, closed-loop synthetic workload with 200 ms outbox polling (normal application default: 1,000 ms). Visibility timings include 100 ms polling. Customer creation is outside the timed interval; HTTP requests route to one instance per service while both asynchronous consumers run. No public-network, browser, email, Docker, sustained-saturation, failover or availability test was performed. Maximum tested concurrency is not maximum supported concurrency.', '',
    'The included `benchmark-results.json` contains individual observations and phase summaries. `BENCHMARKS.md` documents reproduction and method. Percentiles use nearest rank on successful samples; all errors are separately reported.', '']
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text('\n'.join(lines))
print(output)
