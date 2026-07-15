package opentelemetry147.metrics;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.metrics.impl.DDSketchHistograms;
import datadog.opentelemetry.shim.metrics.OtelMeterProvider;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Forked test: runs in an isolated JVM with cumulative temporality, verifying that observable
// counters report absolute values on each collect (no delta computation).
@WithConfig(key = "metrics.otel.enabled", value = "true")
@WithConfig(key = "otlp.metrics.temporality.preference", value = "cumulative")
class OpenTelemetryMetricsCumulativeForkedTest extends AbstractInstrumentationTest {

  private static final Attributes SOME_ATTRIBUTES = Attributes.of(stringKey("some"), "thing");
  private static final String WITH_ATTRS = "@{some=thing}";

  private OtelMeterProvider meterProvider;
  private Meter meter;
  private Map<String, Object> points;
  private OpenTelemetryMetricsTest.MeterReader meterReader;

  @BeforeAll
  static void registerHistogramFactory() {
    datadog.metrics.api.Histograms.register(DDSketchHistograms.FACTORY);
  }

  @BeforeEach
  void setUpMetrics() {
    meterProvider = (OtelMeterProvider) GlobalOpenTelemetry.get().getMeterProvider();
    meter = meterProvider.get("test");
    points = new HashMap<>();
    meterReader = new OpenTelemetryMetricsTest.MeterReader(points);
  }

  @Test
  void testObservableLongCounterCumulative() {
    long[] absoluteValue = {0L};
    AutoCloseable observable =
        meter
            .counterBuilder("cumulative-observable-long-counter")
            .buildWithCallback(m -> m.record(absoluteValue[0]));

    absoluteValue[0] = 5L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(5L, points.get("test:cumulative-observable-long-counter"));

    // cumulative: same absolute value is reported as-is (not as delta=0)
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(5L, points.get("test:cumulative-observable-long-counter"));

    // absolute value increases: reports new absolute value
    points.clear();
    absoluteValue[0] = 12L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(12L, points.get("test:cumulative-observable-long-counter"));

    closeQuietly(observable);
  }

  @Test
  void testObservableDoubleCounterCumulative() {
    double[] absoluteValue = {0.0};
    AutoCloseable observable =
        meter
            .counterBuilder("cumulative-observable-double-counter")
            .ofDoubles()
            .buildWithCallback(m -> m.record(absoluteValue[0]));

    absoluteValue[0] = 3.5;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(3.5, points.get("test:cumulative-observable-double-counter"));

    // cumulative: same absolute value is reported as-is (not as delta=0.0)
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(3.5, points.get("test:cumulative-observable-double-counter"));

    // absolute value increases: reports new absolute value
    points.clear();
    absoluteValue[0] = 8.0;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(8.0, points.get("test:cumulative-observable-double-counter"));

    closeQuietly(observable);
  }

  @Test
  void testSynchronousLongCounterCumulative() {
    io.opentelemetry.api.metrics.LongCounter counter =
        meter.counterBuilder("cumulative-long-counter").build();

    counter.add(1);
    counter.add(2, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(1L, points.get("test:cumulative-long-counter"));
    assertEquals(2L, points.get("test:cumulative-long-counter" + WITH_ATTRS));

    // cumulative: values accumulate without reset between collects
    points.clear();
    counter.add(3);
    counter.add(4, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(4L, points.get("test:cumulative-long-counter"));
    assertEquals(6L, points.get("test:cumulative-long-counter" + WITH_ATTRS));
  }

  @Test
  void testSynchronousDoubleCounterCumulative() {
    io.opentelemetry.api.metrics.DoubleCounter counter =
        meter.counterBuilder("cumulative-double-counter").ofDoubles().build();

    counter.add(1.0);
    counter.add(2.0, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(1.0, points.get("test:cumulative-double-counter"));
    assertEquals(2.0, points.get("test:cumulative-double-counter" + WITH_ATTRS));

    // cumulative: values accumulate without reset between collects
    points.clear();
    counter.add(0.5);
    counter.add(1.5, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(1.5, (double) points.get("test:cumulative-double-counter"), 0.001);
    assertEquals(3.5, (double) points.get("test:cumulative-double-counter" + WITH_ATTRS), 0.001);
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
