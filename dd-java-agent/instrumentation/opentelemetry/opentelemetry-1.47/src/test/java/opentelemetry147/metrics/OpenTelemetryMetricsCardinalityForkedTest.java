package opentelemetry147.metrics;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static java.util.stream.Collectors.toMap;
import static opentelemetry147.metrics.OpenTelemetryMetricsTest.closeQuietly;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.opentelemetry.shim.metrics.OtelMeterProvider;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Forked test: runs in an isolated JVM with a low cardinality limit, verifying that attribute sets
 * an observable callback stops reporting free their cardinality slots.
 */
@WithConfig(key = "metrics.otel.enabled", value = "true")
@WithConfig(key = "metrics.otel.cardinality.limit", value = "2")
class OpenTelemetryMetricsCardinalityForkedTest extends AbstractInstrumentationTest {

  private static final AttributeKey<String> POOL = stringKey("pool");
  private static final String METRIC = "test:pool-connections";

  private Meter meter;
  private Map<String, Object> points;
  private OpenTelemetryMetricsTest.MeterReader meterReader;

  @BeforeEach
  void setUpMetrics() {
    OtelMeterProvider meterProvider =
        (OtelMeterProvider) GlobalOpenTelemetry.get().getMeterProvider();
    meter = meterProvider.get("test");
    points = new HashMap<>();
    meterReader = new OpenTelemetryMetricsTest.MeterReader(points);
  }

  @Test
  void testObservableGaugeFreesSlotsOfUnobservedAttributes() {
    // connections per pool, as reported by the callback
    AtomicReference<Map<String, Long>> pools = new AtomicReference<>(connections("A", 3L, "B", 7L));
    AutoCloseable observable =
        meter
            .gaugeBuilder("pool-connections")
            .ofLongs()
            .buildWithCallback(
                m ->
                    pools
                        .get()
                        .forEach((pool, count) -> m.record(count, Attributes.of(POOL, pool))));

    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(expected(pools.get()), exported());

    // pool B closes and pool C opens: C takes B's slot instead of going to the overflow series
    pools.set(connections("A", 3L, "C", 5L));
    for (int i = 0; i < 3; i++) {
      points.clear();
      OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
      assertEquals(expected(pools.get()), exported());
    }

    closeQuietly(observable);
  }

  /** Connections per pool, for two pools. */
  private static Map<String, Long> connections(
      String pool1, long count1, String pool2, long count2) {
    Map<String, Long> pools = new LinkedHashMap<>();
    pools.put(pool1, count1);
    pools.put(pool2, count2);
    return pools;
  }

  /** Expected data points: one series per pool, with its connections as value. */
  private static Map<String, Object> expected(Map<String, Long> pools) {
    Map<String, Object> expected = new HashMap<>();
    pools.forEach((pool, count) -> expected.put(METRIC + "@{pool=" + pool + "}", count));
    return expected;
  }

  /** Exported data points of the test metric, including any overflow series. */
  private Map<String, Object> exported() {
    return points.entrySet().stream()
        .filter(e -> e.getKey().startsWith(METRIC))
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
