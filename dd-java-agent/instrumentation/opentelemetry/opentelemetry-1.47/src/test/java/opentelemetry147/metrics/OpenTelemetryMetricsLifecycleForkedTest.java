package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

@WithConfig(key = "metrics.otel.enabled", value = "true")
class OpenTelemetryMetricsLifecycleForkedTest extends AbstractInstrumentationTest {

  @Test
  void closingGlobalMeterProviderRunsShutdownHook() throws Exception {
    Closeable meterProvider =
        assertInstanceOf(Closeable.class, GlobalOpenTelemetry.get().getMeterProvider());
    AtomicBoolean shutdownHookCalled = new AtomicBoolean();
    OtelMetricRegistry.INSTANCE.registerShutdownHook(() -> shutdownHookCalled.set(true));

    meterProvider.close();

    assertTrue(shutdownHookCalled.get());
  }
}
