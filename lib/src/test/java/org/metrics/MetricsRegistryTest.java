package org.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MetricsRegistryTest {

    @Test
    void getMeterRegistry_returnsNonNullRegistry() {
        MetricsRegistry metricsRegistry = new MetricsRegistry("test");
        MeterRegistry registry = metricsRegistry.getMeterRegistry();
        assertNotNull(registry);
    }

    @Test
    void scrape_returnsNonEmptyPrometheusOutput() {
        MetricsRegistry metricsRegistry = new MetricsRegistry("test");
        String output = metricsRegistry.scrape();
        assertNotNull(output);
        assertFalse(output.isEmpty());
    }

    @Test
    void scrape_containsJvmMemoryMetrics() {
        MetricsRegistry metricsRegistry = new MetricsRegistry("test");
        String output = metricsRegistry.scrape();
        assertTrue(output.contains("jvm_memory_used_bytes"), "Expected JVM memory metrics to be present");
    }

    @Test
    void scrape_containsJvmThreadMetrics() {
        MetricsRegistry metricsRegistry = new MetricsRegistry("test");
        String output = metricsRegistry.scrape();
        assertTrue(output.contains("jvm_threads"), "Expected JVM thread metrics to be present");
    }

    @Test
    void commonTags_environmentTagIsApplied() {
        MetricsRegistry metricsRegistry = new MetricsRegistry("prod");
        String output = metricsRegistry.scrape();
        assertTrue(output.contains("environment=\"prod\""), "Expected environment tag to be present in scraped output");
    }

    @Test
    void commonTags_serviceTagIsApplied() {
        MetricsRegistry metricsRegistry = new MetricsRegistry("dev");
        String output = metricsRegistry.scrape();
        assertTrue(output.contains("service=\"HealthAIService\""), "Expected service tag to be present in scraped output");
    }

    @Test
    void differentEnvironments_produceSeparateTagValues() {
        MetricsRegistry local = new MetricsRegistry("local");
        MetricsRegistry devRegistry = new MetricsRegistry("dev");

        assertTrue(local.scrape().contains("environment=\"local\""));
        assertTrue(devRegistry.scrape().contains("environment=\"dev\""));
    }
}
