# HealthAI Observability Architecture

This document covers how metrics and logs flow from HealthAI services to Grafana Cloud.

---

## Overview

```
HealthBEService (Java)
  └─ Micrometer OtlpMeterRegistry  ──────────────────────> Grafana Cloud (Metrics)

Docker containers (stdout/stderr)  ─┐
                                    ├─> Promtail  ────────> Grafana Cloud (Loki)
Nginx access logs                  ─┘
```

There are two independent pipelines:
- **Metrics** — pushed directly from the Java service via OTLP. No sidecar.
- **Logs** — collected externally by a Promtail sidecar and pushed to Loki.

All telemetry lands in the same Grafana Cloud account. Credentials are stored in AWS Secrets Manager under the secret `HealthAI-GrafanaCloud`.

---

## Metrics Pipeline

### What is OTLP?

OTLP (OpenTelemetry Protocol) is a vendor-neutral standard for transmitting telemetry data. Your service sends metrics as HTTP POST requests with Protobuf-encoded bodies directly to Grafana Cloud's OTLP endpoint — no Prometheus server or scraping agent required.

### Flow

```
HealthApiImp.java
  └─ metricsRecorder.recordCount("upload", "success")
       └─ MetricsRecorder
            └─ Micrometer Counter / Timer
                 └─ CompositeMeterRegistry
                      ├─ PrometheusMeterRegistry  →  GET /metrics  (local debug only)
                      └─ OtlpMeterRegistry        →  HTTPS POST every 60s  →  Grafana Cloud
```

### Instrumentation

Every API endpoint in `HealthApiImp.java` records two metrics per request:

```java
long start = System.nanoTime();
try {
    // handler logic
    metricsRecorder.recordCount("upload", "success");
} catch (Exception e) {
    metricsRecorder.recordCount("upload", "server_error");
} finally {
    metricsRecorder.recordLatency("upload", System.nanoTime() - start);
}
```

| Metric | Type | Tags |
|---|---|---|
| `api_request_count_total` | Counter | `operation`, `status`, `environment`, `service` |
| `api_request_duration_seconds` | Histogram | `operation`, `environment`, `service` |

`operation` is one of: `upload`, `download`, `query`, `presign`, `feedback`  
`status` is one of: `success`, `client_error` (4xx), `server_error` (5xx)

JVM metrics (heap, GC, threads, CPU) are collected automatically via Micrometer binders registered at startup.

### Registries

`MetricsRegistry.java` creates the `PrometheusMeterRegistry` — an in-memory store that serves `GET /metrics` in Prometheus text format. This is useful for local debugging but nothing scrapes it in production.

`MetricsModule.java` (Dagger) wraps it in a `CompositeMeterRegistry` at startup and adds `OtlpMeterRegistry`, which is the actual production path. The composite fans out every metric write to both registries simultaneously.

### Credentials

At JVM startup, `MetricsModule.java` calls AWS Secrets Manager to fetch `HealthAI-GrafanaCloud` and extracts:

| Secret Field | Used for |
|---|---|
| `GRAFANA_OTLP_URL` | OTLP endpoint URL |
| `GRAFANA_INSTANCE_ID` | Basic Auth username |
| `GRAFANA_API_KEY` | Basic Auth password |

If the Secrets Manager call fails (e.g. no AWS credentials locally), OTLP push is skipped and metrics are only available at `GET /metrics`. The service starts normally either way.

### Key Files

| File | Purpose |
|---|---|
| `MetricsLibrary/lib/src/.../MetricsRegistry.java` | Creates Prometheus registry, binds JVM metrics |
| `MetricsLibrary/lib/src/.../MetricsRecorder.java` | Public API: `recordCount`, `recordLatency` |
| `HealthBEService/app/src/.../MetricsModule.java` | Dagger wiring, fetches credentials, builds composite registry |
| `HealthBEService/app/src/.../HealthApiImp.java` | Instrumentation at each endpoint |
| `HealthBEService/app/src/.../MetricsResource.java` | Exposes `GET /metrics` for local scraping |

---

## Logs Pipeline

### What is Promtail?

Promtail is a log shipping agent made by Grafana. It runs as a sidecar container, reads log streams from Docker and from files, and pushes them to Loki (Grafana's log storage backend) on Grafana Cloud.

### Flow

```
Docker socket (container stdout/stderr)
  └─ Promtail (docker_sd_configs)
       ├─ relabel: container, service, project, environment
       ├─ parse Log4j2 format → extract: thread, level, logger
       └─ push to Loki

/var/log/nginx/access.log
  └─ Promtail (static_configs)
       ├─ parse nginx format → extract: method, status, path, remote_addr
       └─ push to Loki
```

### Two Scrape Jobs

**`healthai_containers`** — watches the Docker socket and captures stdout/stderr from all running containers. For the `healthai` service it parses the Log4j2 log format:

```
^\S+ [thread] LEVEL logger - message
```

This promotes `level` and `logger` as Loki labels, enabling filtering like `{level="ERROR"}` in Grafana.

**`nginx`** — reads `/var/log/nginx/access.log` as a static file. Parses each line to extract `method`, `status`, `path`, and `remote_addr` as labels. This is what powers the 429 rate limiting tracking in the nginx dashboard.

### Credentials

Promtail credentials are passed as environment variables to the container. In CI/production (`docker-compose.yml`) they are injected directly. For local dev (`docker-compose.local.yml`), run the setup script once before `docker compose up`:

```bash
./scripts/setup-loki-env.sh
```

This fetches `HealthAI-GrafanaCloud` from AWS Secrets Manager and writes `promtail/.env.loki`:

| Secret Field | Maps to |
|---|---|
| `GRAFANA_LOKI_URL` | `LOKI_URL` |
| `GRAFANA_LOKI_INSTANCE_ID` | `LOKI_INSTANCE_ID` |
| `GRAFANA_API_KEY` | `LOKI_API_KEY` |

### Key Files

| File | Purpose |
|---|---|
| `promtail/config.yml` | Scrape jobs, label extraction, Loki push config |
| `promtail/.env.loki` | Loki credentials (gitignored, generated by setup script) |
| `scripts/setup-loki-env.sh` | Fetches credentials from Secrets Manager, writes `.env.loki` |

---

## Dashboards

Three pre-built Grafana dashboards live in `MetricsLibrary/dashboards/`. Import them via the Grafana Cloud UI.

| Dashboard | Source | Key Panels |
|---|---|---|
| `api-dashboard.json` | Metrics (OTLP) | Request rate, availability %, p95/p99 latency, error rate by type |
| `jvm-dashboard.json` | Metrics (OTLP) | Heap usage, GC pause rate, thread count, CPU usage |
| `nginx-dashboard.json` | Logs (Loki) | Request rate, 429 throttle rate, status code breakdown |

The nginx dashboard queries Loki log labels (`status`, `method`) rather than metrics — this is why Promtail label extraction for nginx matters.

---

## Metrics vs Logs Comparison

| | Metrics | Logs |
|---|---|---|
| Pipeline | OTLP push from Java app | Promtail sidecar reads externally |
| Sidecar required | No | Yes — Promtail container |
| Destination | Grafana Cloud (OTLP endpoint) | Grafana Cloud (Loki) |
| Credentials source | AWS Secrets Manager at JVM startup | `promtail/.env.loki` at container start |
| Covers Nginx | No | Yes |
| Covers Python service | No | Yes (stdout captured by Docker job) |

---

## What is Not Monitored

- **HealthInferenceService (Python)** — no metrics instrumentation. Logs are captured via the Docker scrape job but no structured parsing is applied.
- **Frontend (React)** — no client-side error or performance tracking.
