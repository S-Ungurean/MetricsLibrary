package org.metrics;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class MetricsRecorder {

    private final MeterRegistry registry;

    @Inject
    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Increments a success or failure counter for the given operation.
     * Emits: api_request_count_total{operation, status}
     */
    public void recordCount(String operation, boolean success) {
        Counter.builder("api.request.count")
            .tag("operation", operation)
            .tag("status", success ? "success" : "failure")
            .register(registry)
            .increment();
    }

    /**
     * Records how long an operation took. Enables p95/p99 via histogram_quantile() in Grafana.
     * Emits: api_request_duration_seconds_count / _sum / _bucket{operation}
     */
    public void recordLatency(String operation, long durationNs) {
        Timer.builder("api.request.duration")
            .tag("operation", operation)
            .publishPercentileHistogram()
            .register(registry)
            .record(durationNs, TimeUnit.NANOSECONDS);
    }

    /**
     * Increments a named counter with optional key-value tag pairs.
     * Tags must be alternating key, value strings e.g. ("llm.retry", "model", "gpt4")
     * Emits: <name>_total{<tags>}
     */
    public void incrementCounter(String name, String... tags) {
        Counter.builder(name)
            .tags(tags)
            .register(registry)
            .increment();
    }
}
