package org.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private MetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new MetricsRecorder(registry);
    }

    @Test
    void recordCount_successIncrementsSuccessCounter() {
        recorder.recordCount("upload", true);

        Counter counter = registry.find("api.request.count")
                .tag("operation", "upload")
                .tag("status", "success")
                .counter();

        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recordCount_failureIncrementsFailureCounter() {
        recorder.recordCount("query", false);

        Counter counter = registry.find("api.request.count")
                .tag("operation", "query")
                .tag("status", "failure")
                .counter();

        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recordCount_multipleCallsAccumulate() {
        recorder.recordCount("presign", true);
        recorder.recordCount("presign", true);
        recorder.recordCount("presign", true);

        Counter counter = registry.find("api.request.count")
                .tag("operation", "presign")
                .tag("status", "success")
                .counter();

        assertNotNull(counter);
        assertEquals(3.0, counter.count());
    }

    @Test
    void recordCount_successAndFailureAreIndependentCounters() {
        recorder.recordCount("download", true);
        recorder.recordCount("download", false);
        recorder.recordCount("download", false);

        Counter success = registry.find("api.request.count")
                .tag("operation", "download")
                .tag("status", "success")
                .counter();
        Counter failure = registry.find("api.request.count")
                .tag("operation", "download")
                .tag("status", "failure")
                .counter();

        assertNotNull(success);
        assertNotNull(failure);
        assertEquals(1.0, success.count());
        assertEquals(2.0, failure.count());
    }

    @Test
    void recordLatency_registersTimer() {
        recorder.recordLatency("upload", 150_000_000L);

        Timer timer = registry.find("api.request.duration")
                .tag("operation", "upload")
                .timer();

        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void recordLatency_multipleRecordsAccumulate() {
        recorder.recordLatency("query", 100_000_000L);
        recorder.recordLatency("query", 200_000_000L);

        Timer timer = registry.find("api.request.duration")
                .tag("operation", "query")
                .timer();

        assertNotNull(timer);
        assertEquals(2, timer.count());
    }

    @Test
    void incrementCounter_registersNamedCounter() {
        recorder.incrementCounter("llm.retry");

        Counter counter = registry.find("llm.retry").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void incrementCounter_withTags_registersTaggedCounter() {
        recorder.incrementCounter("upload.async.failure", "operation", "model_analysis");

        Counter counter = registry.find("upload.async.failure")
                .tag("operation", "model_analysis")
                .counter();

        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void incrementCounter_multipleCallsAccumulate() {
        recorder.incrementCounter("llm.retry");
        recorder.incrementCounter("llm.retry");

        Counter counter = registry.find("llm.retry").counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }
}
