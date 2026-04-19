# HealthAI Metrics Design

## Overview

HealthAI uses [Micrometer](https://micrometer.io/) as a metrics facade to collect and emit observability data from the backend service. Metrics are pushed directly to [Grafana Cloud](https://grafana.com/products/cloud/) via the OpenTelemetry Protocol (OTLP) and also exposed locally via a Prometheus scrape endpoint at `GET /metrics`.

---

## Architecture

```
HealthAPIHandler
      │
      ▼
MetricsRecorder          (MetricsLibrary — shared library)
      │
      ▼
CompositeMeterRegistry
      ├── PrometheusMeterRegistry  ──> GET /metrics  (local debugging)
      └── OtlpMeterRegistry        ──> Grafana Cloud  (dashboards & alerts)
                                           ▲
                               Credentials from AWS Secrets Manager
                               (HealthAI-GrafanaCloud)
```

The `CompositeMeterRegistry` means every metric recorded by `MetricsRecorder` is written to both registries simultaneously — no duplication of instrumentation code.

---

## Module Structure

Metrics live in a dedicated shared library `MetricsLibrary` so any future service (HealthSAO, HealthDAO) can import and emit metrics using the same API without depending on `HealthBEService`.

```
MetricsLibrary/lib/src/main/java/org/metrics/
├── MetricsRegistry.java    — registry setup, JVM binders, common tags
└── MetricsRecorder.java    — public API for recording metrics

MetricsLibrary/dashboards/
├── api-dashboard.json      — Grafana API metrics dashboard
└── jvm-dashboard.json      — Grafana JVM metrics dashboard
```

---

## Common Tags

Every metric emitted carries two common tags stamped at registry creation time:

| Tag | Value | Source |
|---|---|---|
| `environment` | `local`, `dev`, `prod` | `APP_ENV` env var, defaults to `local` |
| `service` | `HealthAIService` | Hardcoded at registry level |

This allows Grafana dashboards to filter by environment or service without any changes to individual metric calls.

---

## Metric Types

### `recordCount(String operation, boolean success)`
Increments a counter tracking the outcome of an API operation.

**Instrument:** Micrometer `Counter`

**Emits:**
```
api_request_count_total{operation, status, environment, service}
```

**Tags:**
- `operation` — the handler name (`upload`, `query`, `presign`, `download`, `feedback`)
- `status` — `success` or `failure`

**Example:**
```
api_request_count_total{operation="upload", status="success", environment="dev", service="HealthAIService"} 42
api_request_count_total{operation="upload", status="failure", environment="dev", service="HealthAIService"} 3
```

---

### `recordLatency(String operation, long durationNs)`
Records how long an operation took in nanoseconds. Emits histogram buckets enabling p95/p99 percentile queries in Grafana.

**Instrument:** Micrometer `Timer` with `publishPercentileHistogram()`

**Emits:**
```
api_request_duration_seconds_count{operation, environment, service}
api_request_duration_seconds_sum{operation, environment, service}
api_request_duration_seconds_bucket{operation, environment, service, le}
```

**Why histogram over pre-computed percentiles:**
`publishPercentileHistogram()` sends raw histogram buckets to Grafana Cloud. Percentiles are computed server-side via `histogram_quantile()`. This approach is aggregatable across multiple service instances, unlike pre-computed percentiles which cannot be meaningfully averaged.

**Grafana query for p95:**
```promql
histogram_quantile(0.95, sum by(operation, le) (
  rate(api_request_duration_seconds_bucket{environment=~"$environment"}[5m])
))
```

---

### `incrementCounter(String name, String... tags)`
General purpose counter for events that don't require latency tracking — LLM retries, async failures, cache hits etc.

**Instrument:** Micrometer `Counter`

**Emits:**
```
<name>_total{<tags>, environment, service}
```

**Example usage:**
```java
metricsRecorder.incrementCounter("upload.async.failure", "operation", "model_analysis");
```

---

## JVM Metrics

Collected automatically at registry startup via Micrometer binders. No instrumentation code required.

| Metric | Description |
|---|---|
| `jvm_memory_used_bytes` | Heap and non-heap memory used |
| `jvm_memory_max_bytes` | Maximum heap size |
| `jvm_gc_pause_seconds` | GC pause duration histogram |
| `jvm_threads_live_threads` | Current live thread count |
| `jvm_threads_daemon_threads` | Daemon thread count |
| `jvm_threads_peak_threads` | Peak thread count since JVM start |
| `system_cpu_usage` | System-wide CPU utilisation |
| `process_cpu_usage` | CPU used by this JVM process |

---

## Handler Instrumentation Pattern

Every handler in `HealthAPIHandler` follows the same pattern:

```java
long start = System.nanoTime();
try {
    // ... handler logic ...
    metricsRecorder.recordCount("operation", true);
    return result;
} catch (Exception e) {
    metricsRecorder.recordCount("operation", false);
    throw e;
} finally {
    metricsRecorder.recordLatency("operation", System.nanoTime() - start);
}
```

- `recordCount` is inside try/catch — captures success vs failure
- `recordLatency` is in `finally` — always records duration regardless of outcome
- Async background failures (model inference) use `incrementCounter` since they execute outside the request thread

---

## Credentials & Security

Grafana Cloud credentials are stored in AWS Secrets Manager under `HealthAI-GrafanaCloud`:

```json
{
  "GRAFANA_OTLP_URL":    "https://otlp-gateway-prod-<region>.grafana.net/otlp/v1/metrics",
  "GRAFANA_INSTANCE_ID": "<numeric-stack-id>",
  "GRAFANA_API_KEY":     "glc_..."
}
```

The IAM policy on the dev EC2 instance (`DevStack.ts`) explicitly grants `secretsmanager:GetSecretValue` on this secret. Locally, credentials are read via the mounted `~/.aws` profile.

The OTLP registry authenticates using HTTP Basic auth (`instanceId:apiKey` Base64 encoded), which is the format required by Grafana Cloud's OTLP gateway.

The Access Policy token (`healthai-metrics-push`) is scoped to `metrics:write` only — it cannot read dashboards, modify alerts, or access other Grafana resources.

---

## Dashboards

Two pre-built Grafana dashboards are stored in `MetricsLibrary/dashboards/` and can be imported via Grafana UI (**Dashboards → New → Import**).

| File | Title | Contents |
|---|---|---|
| `api-dashboard.json` | HealthAI — API Metrics | Success/failure rates, availability %, p95/p99 latency per operation |
| `jvm-dashboard.json` | HealthAI — JVM Metrics | Heap, non-heap, threads, CPU, GC pause rate |

Both dashboards include dropdowns for **Environment** (local/dev/prod) and **Service** (HealthAIService) that auto-populate from live metric labels.

---

## Adding Metrics to a New Service

To instrument a new service (e.g. HealthSAO):

1. Add `MetricsLibrary` as a Gradle dependency:
```kotlin
implementation(project(":MetricsLibrary:lib"))
```

2. Inject `MetricsRecorder` via constructor:
```java
private final MetricsRecorder metricsRecorder;
```

3. Record metrics using the same API:
```java
metricsRecorder.recordCount("llm.classify", true);
metricsRecorder.recordLatency("llm.classify", System.nanoTime() - start);
```

The `MeterRegistry` binding and Grafana Cloud push are handled entirely by `HealthBEService`'s `MetricsModule` — no additional configuration needed in the library.
