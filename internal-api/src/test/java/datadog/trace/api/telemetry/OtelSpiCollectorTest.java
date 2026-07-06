package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OtelSpiCollectorTest {

  private final OtelSpiCollector collector = OtelSpiCollector.getInstance();

  @BeforeEach
  public void clearQueue() {
    collector.drain();
  }

  @Test
  public void singletonReturnsSameInstance() {
    assertSame(OtelSpiCollector.getInstance(), OtelSpiCollector.getInstance());
  }

  @Test
  public void recordedMetricSurfacesOnDrain() {
    collector.recordSpiDetected(
        "io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider", "extensions_path");

    Iterator<OtelSpiCollector.OtelSpiMetric> drained = collector.drain().iterator();
    assertTrue(drained.hasNext());
    OtelSpiCollector.OtelSpiMetric metric = drained.next();
    assertEquals("tracers", metric.namespace);
    assertEquals("otel.spi.detected", metric.metricName);
    assertEquals("count", metric.type);
    assertTrue(metric.common);
    assertEquals(1, metric.value);
    assertTrue(
        metric.tags.contains(
            "spi_class:io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider"));
    assertTrue(metric.tags.contains("source:extensions_path"));
  }

  @Test
  public void multipleRecordsDrainAsDistinctMetrics() {
    collector.recordSpiDetected(
        "io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider", "app_classpath");
    collector.recordSpiDetected(
        "io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider", "app_classpath");
    collector.recordSpiDetected(
        "io.opentelemetry.sdk.autoconfigure.spi.ConfigurableSamplerProvider", "extensions_path");

    assertEquals(3, collector.drain().size());
  }

  @Test
  public void drainWithoutRecordsReturnsEmpty() {
    assertEquals(0, collector.drain().size());
  }

  @Test
  public void drainClearsQueue() {
    collector.recordSpiDetected(
        "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider",
        "extensions_path");
    assertEquals(1, collector.drain().size());
    assertEquals(0, collector.drain().size());
  }
}
