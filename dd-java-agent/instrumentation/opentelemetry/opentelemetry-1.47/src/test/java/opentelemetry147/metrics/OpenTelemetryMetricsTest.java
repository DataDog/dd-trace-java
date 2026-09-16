package opentelemetry147.metrics;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.metrics.impl.DDSketchHistograms;
import datadog.opentelemetry.shim.metrics.OtelMeterProvider;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "metrics.otel.enabled", value = "true")
class OpenTelemetryMetricsTest extends AbstractInstrumentationTest {

  private static final Attributes SOME_ATTRIBUTES = Attributes.of(stringKey("some"), "thing");
  private static final String WITH_ATTRS = "@{some=thing}";

  private OtelMeterProvider meterProvider;
  private Meter meter;
  private Map<String, Object> points;
  private MeterReader meterReader;

  @BeforeAll
  static void registerHistogramFactory() {
    datadog.metrics.api.Histograms.register(DDSketchHistograms.FACTORY);
  }

  @BeforeEach
  void setUpMetrics() {
    meterProvider = (OtelMeterProvider) GlobalOpenTelemetry.get().getMeterProvider();
    meter = meterProvider.get("test");
    points = new HashMap<>();
    meterReader = new MeterReader(points);
  }

  @Test
  void testLongCounter() {
    io.opentelemetry.api.metrics.LongCounter counter = meter.counterBuilder("long-counter").build();
    counter.add(1);
    counter.add(2, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:long-counter"));
    assertEquals(2L, points.get("test:long-counter" + WITH_ATTRS));
  }

  @Test
  void testDoubleCounter() {
    io.opentelemetry.api.metrics.DoubleCounter counter =
        meter.counterBuilder("double-counter").ofDoubles().build();
    counter.add(1.2);
    counter.add(3.4, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:double-counter"));
    assertEquals(3.4, points.get("test:double-counter" + WITH_ATTRS));
  }

  @Test
  void testLongUpDownCounter() {
    io.opentelemetry.api.metrics.LongUpDownCounter counter =
        meter.upDownCounterBuilder("long-up-down-counter").build();
    counter.add(1);
    counter.add(2, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:long-up-down-counter"));
    assertEquals(2L, points.get("test:long-up-down-counter" + WITH_ATTRS));
  }

  @Test
  void testDoubleUpDownCounter() {
    io.opentelemetry.api.metrics.DoubleUpDownCounter counter =
        meter.upDownCounterBuilder("double-up-down-counter").ofDoubles().build();
    counter.add(1.2);
    counter.add(3.4, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:double-up-down-counter"));
    assertEquals(3.4, points.get("test:double-up-down-counter" + WITH_ATTRS));
  }

  @Test
  void testLongGauge() {
    io.opentelemetry.api.metrics.LongGauge gauge =
        meter.gaugeBuilder("long-gauge").ofLongs().build();
    gauge.set(1);
    gauge.set(2, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:long-gauge"));
    assertEquals(2L, points.get("test:long-gauge" + WITH_ATTRS));
  }

  @Test
  void testDoubleGauge() {
    io.opentelemetry.api.metrics.DoubleGauge gauge = meter.gaugeBuilder("double-gauge").build();
    gauge.set(1.2);
    gauge.set(3.4, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:double-gauge"));
    assertEquals(3.4, points.get("test:double-gauge" + WITH_ATTRS));
  }

  @Test
  void testLongHistogram() {
    io.opentelemetry.api.metrics.LongHistogram histogram =
        meter.histogramBuilder("long-histogram").ofLongs().build();
    histogram.record(1);
    histogram.record(24);
    histogram.record(101, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(
        new HistogramData(
            2.0, Arrays.asList(0.0, 5.0, 10.0, 25.0), Arrays.asList(0.0, 1.0, 0.0, 1.0), 25.0),
        points.get("test:long-histogram"));
    assertEquals(
        new HistogramData(1.0, Arrays.asList(100.0, 250.0), Arrays.asList(0.0, 1.0), 101.0),
        points.get("test:long-histogram" + WITH_ATTRS));
  }

  @Test
  void testDoubleHistogram() {
    io.opentelemetry.api.metrics.DoubleHistogram histogram =
        meter.histogramBuilder("double-histogram").build();
    histogram.record(1.2);
    histogram.record(24.5);
    histogram.record(101.2, SOME_ATTRIBUTES);
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(
        new HistogramData(
            2.0, Arrays.asList(0.0, 5.0, 10.0, 25.0), Arrays.asList(0.0, 1.0, 0.0, 1.0), 25.7),
        points.get("test:double-histogram"));
    assertEquals(
        new HistogramData(1.0, Arrays.asList(100.0, 250.0), Arrays.asList(0.0, 1.0), 101.2),
        points.get("test:double-histogram" + WITH_ATTRS));
  }

  @Test
  void testLongHistogramOverflow() {
    io.opentelemetry.api.metrics.LongHistogram histogram =
        meter.histogramBuilder("long-histogram-overflow").ofLongs().build();
    histogram.record(20_000); // exceeds highest default boundary of 10_000
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(
        new HistogramData(
            1.0,
            Arrays.asList(10_000.0, Double.POSITIVE_INFINITY),
            Arrays.asList(0.0, 1.0),
            20_000.0),
        points.get("test:long-histogram-overflow"));
  }

  @Test
  void testDoubleHistogramOverflow() {
    io.opentelemetry.api.metrics.DoubleHistogram histogram =
        meter.histogramBuilder("double-histogram-overflow").build();
    histogram.record(20_000.5); // exceeds highest default boundary of 10_000
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(
        new HistogramData(
            1.0,
            Arrays.asList(10_000.0, Double.POSITIVE_INFINITY),
            Arrays.asList(0.0, 1.0),
            20_000.5),
        points.get("test:double-histogram-overflow"));
  }

  @Test
  void testObservableLongCounter() {
    AutoCloseable observable =
        meter
            .counterBuilder("observable-long-counter")
            .buildWithCallback(
                m -> {
                  m.record(1);
                  m.record(2, SOME_ATTRIBUTES);
                });
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:observable-long-counter"));
    assertEquals(2L, points.get("test:observable-long-counter" + WITH_ATTRS));

    // second collect: absolute values are reported, not accumulated
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(0L, points.get("test:observable-long-counter"));
    assertEquals(0L, points.get("test:observable-long-counter" + WITH_ATTRS));

    closeQuietly(observable);
  }

  @Test
  void testObservableDoubleCounter() {
    AutoCloseable observable =
        meter
            .counterBuilder("observable-double-counter")
            .ofDoubles()
            .buildWithCallback(
                m -> {
                  m.record(1.2);
                  m.record(3.4, SOME_ATTRIBUTES);
                });
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:observable-double-counter"));
    assertEquals(3.4, points.get("test:observable-double-counter" + WITH_ATTRS));

    // second collect: absolute values are reported, not accumulated
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(0.0, points.get("test:observable-double-counter"));
    assertEquals(0.0, points.get("test:observable-double-counter" + WITH_ATTRS));

    closeQuietly(observable);
  }

  @Test
  void testObservableLongUpDownCounter() {
    AutoCloseable observable =
        meter
            .upDownCounterBuilder("observable-long-up-down-counter")
            .buildWithCallback(
                m -> {
                  m.record(1);
                  m.record(2, SOME_ATTRIBUTES);
                });
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:observable-long-up-down-counter"));
    assertEquals(2L, points.get("test:observable-long-up-down-counter" + WITH_ATTRS));

    // second collect: absolute values are reported, not accumulated
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:observable-long-up-down-counter"));
    assertEquals(2L, points.get("test:observable-long-up-down-counter" + WITH_ATTRS));

    closeQuietly(observable);
  }

  @Test
  void testObservableDoubleUpDownCounter() {
    AutoCloseable observable =
        meter
            .upDownCounterBuilder("observable-double-up-down-counter")
            .ofDoubles()
            .buildWithCallback(
                m -> {
                  m.record(1.2);
                  m.record(3.4, SOME_ATTRIBUTES);
                });
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:observable-double-up-down-counter"));
    assertEquals(3.4, points.get("test:observable-double-up-down-counter" + WITH_ATTRS));

    // second collect: absolute values are reported, not accumulated
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:observable-double-up-down-counter"));
    assertEquals(3.4, points.get("test:observable-double-up-down-counter" + WITH_ATTRS));

    closeQuietly(observable);
  }

  @Test
  void testObservableLongGauge() {
    AutoCloseable observable =
        meter
            .gaugeBuilder("observable-long-gauge")
            .ofLongs()
            .buildWithCallback(
                m -> {
                  m.record(1);
                  m.record(2, SOME_ATTRIBUTES);
                });
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:observable-long-gauge"));
    assertEquals(2L, points.get("test:observable-long-gauge" + WITH_ATTRS));

    closeQuietly(observable);
  }

  @Test
  void testObservableDoubleGauge() {
    AutoCloseable observable =
        meter
            .gaugeBuilder("observable-double-gauge")
            .buildWithCallback(
                m -> {
                  m.record(1.2);
                  m.record(3.4, SOME_ATTRIBUTES);
                });
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1.2, points.get("test:observable-double-gauge"));
    assertEquals(3.4, points.get("test:observable-double-gauge" + WITH_ATTRS));

    closeQuietly(observable);
  }

  @Test
  void testObservableLongCounterDeltaWithChangingValues() {
    long[] absoluteValue = {0L};
    AutoCloseable observable =
        meter
            .counterBuilder("observable-long-counter-delta-changing")
            .buildWithCallback(m -> m.record(absoluteValue[0]));

    absoluteValue[0] = 5L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(5L, points.get("test:observable-long-counter-delta-changing"));

    // delta since last collect: 12 - 5 = 7
    points.clear();
    absoluteValue[0] = 12L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(7L, points.get("test:observable-long-counter-delta-changing"));

    // no change in absolute value: delta = 0
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(0L, points.get("test:observable-long-counter-delta-changing"));

    closeQuietly(observable);
  }

  @Test
  void testObservableDoubleCounterDeltaWithChangingValues() {
    double[] absoluteValue = {0.0};
    AutoCloseable observable =
        meter
            .counterBuilder("observable-double-counter-delta-changing")
            .ofDoubles()
            .buildWithCallback(m -> m.record(absoluteValue[0]));

    absoluteValue[0] = 2.5;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(2.5, points.get("test:observable-double-counter-delta-changing"));

    // delta since last collect: 5.0 - 2.5 = 2.5
    points.clear();
    absoluteValue[0] = 5.0;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(2.5, points.get("test:observable-double-counter-delta-changing"));

    // no change in absolute value: delta = 0.0
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(0.0, points.get("test:observable-double-counter-delta-changing"));

    closeQuietly(observable);
  }

  @Test
  void testObservableLongUpDownCounterReportsAbsoluteValue() {
    long[] absoluteValue = {0L};
    AutoCloseable observable =
        meter
            .upDownCounterBuilder("observable-long-up-down-counter-absolute")
            .buildWithCallback(m -> m.record(absoluteValue[0]));

    absoluteValue[0] = 10L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(10L, points.get("test:observable-long-up-down-counter-absolute"));

    // value decreases: should report new absolute value, not a delta
    points.clear();
    absoluteValue[0] = 3L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(3L, points.get("test:observable-long-up-down-counter-absolute"));

    // value increases again
    points.clear();
    absoluteValue[0] = 15L;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(15L, points.get("test:observable-long-up-down-counter-absolute"));

    closeQuietly(observable);
  }

  @Test
  void testObservableDoubleUpDownCounterReportsAbsoluteValue() {
    double[] absoluteValue = {0.0};
    AutoCloseable observable =
        meter
            .upDownCounterBuilder("observable-double-up-down-counter-absolute")
            .ofDoubles()
            .buildWithCallback(m -> m.record(absoluteValue[0]));

    absoluteValue[0] = 8.0;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(8.0, points.get("test:observable-double-up-down-counter-absolute"));

    // value decreases: should report new absolute value
    points.clear();
    absoluteValue[0] = 2.5;
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);
    assertEquals(2.5, points.get("test:observable-double-up-down-counter-absolute"));

    closeQuietly(observable);
  }

  @Test
  void testBatchCallback() {
    ObservableLongMeasurement longCounterObserver =
        meter.counterBuilder("long-counter-observer").buildObserver();
    ObservableDoubleMeasurement doubleCounterObserver =
        meter.counterBuilder("double-counter-observer").ofDoubles().buildObserver();
    ObservableLongMeasurement longUpDownCounterObserver =
        meter.upDownCounterBuilder("long-up-down-counter-observer").buildObserver();
    ObservableDoubleMeasurement doubleUpDownCounterObserver =
        meter.upDownCounterBuilder("double-up-down-counter-observer").ofDoubles().buildObserver();
    ObservableLongMeasurement longGaugeObserver =
        meter.gaugeBuilder("long-gauge-observer").ofLongs().buildObserver();
    ObservableDoubleMeasurement doubleGaugeObserver =
        meter.gaugeBuilder("double-gauge-observer").buildObserver();

    BatchCallback batchCallback =
        meter.batchCallback(
            () -> {
              longCounterObserver.record(1);
              longCounterObserver.record(10, SOME_ATTRIBUTES);
              doubleCounterObserver.record(2.3);
              doubleCounterObserver.record(20.3, SOME_ATTRIBUTES);
              longUpDownCounterObserver.record(4);
              longUpDownCounterObserver.record(40, SOME_ATTRIBUTES);
              doubleUpDownCounterObserver.record(5.6);
              doubleUpDownCounterObserver.record(50.6, SOME_ATTRIBUTES);
              longGaugeObserver.record(7);
              longGaugeObserver.record(70, SOME_ATTRIBUTES);
              doubleGaugeObserver.record(8.9);
              doubleGaugeObserver.record(80.9, SOME_ATTRIBUTES);
            },
            longCounterObserver,
            doubleCounterObserver,
            longUpDownCounterObserver,
            doubleUpDownCounterObserver,
            longGaugeObserver,
            doubleGaugeObserver);

    // this callback will have no effect because it doesn't declare any measurements
    BatchCallback noopCallback =
        meter.batchCallback(
            () -> {
              longCounterObserver.record(1000);
              longCounterObserver.record(1000, SOME_ATTRIBUTES);
              doubleCounterObserver.record(1000);
              doubleCounterObserver.record(1000, SOME_ATTRIBUTES);
              longUpDownCounterObserver.record(1000);
              longUpDownCounterObserver.record(1000, SOME_ATTRIBUTES);
              doubleUpDownCounterObserver.record(1000);
              doubleUpDownCounterObserver.record(1000, SOME_ATTRIBUTES);
              longGaugeObserver.record(1000);
              longGaugeObserver.record(1000, SOME_ATTRIBUTES);
              doubleGaugeObserver.record(1000);
              doubleGaugeObserver.record(1000, SOME_ATTRIBUTES);
            },
            (ObservableMeasurement) null);

    // first collect
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    assertEquals(1L, points.get("test:long-counter-observer"));
    assertEquals(10L, points.get("test:long-counter-observer" + WITH_ATTRS));
    assertEquals(2.3, points.get("test:double-counter-observer"));
    assertEquals(20.3, points.get("test:double-counter-observer" + WITH_ATTRS));
    assertEquals(4L, points.get("test:long-up-down-counter-observer"));
    assertEquals(40L, points.get("test:long-up-down-counter-observer" + WITH_ATTRS));
    assertEquals(5.6, points.get("test:double-up-down-counter-observer"));
    assertEquals(50.6, points.get("test:double-up-down-counter-observer" + WITH_ATTRS));
    assertEquals(7L, points.get("test:long-gauge-observer"));
    assertEquals(70L, points.get("test:long-gauge-observer" + WITH_ATTRS));
    assertEquals(8.9, points.get("test:double-gauge-observer"));
    assertEquals(80.9, points.get("test:double-gauge-observer" + WITH_ATTRS));

    // second collect: batchCallback is invoked again
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    // async counters show delta since last collect (same value was recorded)
    assertEquals(0L, points.get("test:long-counter-observer"));
    assertEquals(0L, points.get("test:long-counter-observer" + WITH_ATTRS));
    assertEquals(0.0, points.get("test:double-counter-observer"));
    assertEquals(0.0, points.get("test:double-counter-observer" + WITH_ATTRS));
    // async up-down counters stay cumulative and show the latest value
    assertEquals(4L, points.get("test:long-up-down-counter-observer"));
    assertEquals(40L, points.get("test:long-up-down-counter-observer" + WITH_ATTRS));
    assertEquals(5.6, (double) points.get("test:double-up-down-counter-observer"), 0.001);
    assertEquals(
        50.6, (double) points.get("test:double-up-down-counter-observer" + WITH_ATTRS), 0.001);
    // gauges continue to only show the latest value
    assertEquals(7L, points.get("test:long-gauge-observer"));
    assertEquals(70L, points.get("test:long-gauge-observer" + WITH_ATTRS));
    assertEquals(8.9, points.get("test:double-gauge-observer"));
    assertEquals(80.9, points.get("test:double-gauge-observer" + WITH_ATTRS));

    // third collect: batchCallback is closed, so it will not be invoked
    batchCallback.close();
    points.clear();
    OtelMetricRegistry.INSTANCE.collectMetrics(meterReader);

    // delta mode: no counts were set as batchCallback is closed, so no data point
    assertNull(points.get("test:long-counter-observer"));
    assertNull(points.get("test:long-counter-observer" + WITH_ATTRS));
    assertNull(points.get("test:double-counter-observer"));
    assertNull(points.get("test:double-counter-observer" + WITH_ATTRS));
    // up-down counters stay cumulative: they continue to show the last count set
    assertEquals(4L, points.get("test:long-up-down-counter-observer"));
    assertEquals(40L, points.get("test:long-up-down-counter-observer" + WITH_ATTRS));
    assertEquals(5.6, (double) points.get("test:double-up-down-counter-observer"), 0.001);
    assertEquals(
        50.6, (double) points.get("test:double-up-down-counter-observer" + WITH_ATTRS), 0.001);
    // gauges also stay cumulative: they continue to show the latest value set
    assertEquals(7L, points.get("test:long-gauge-observer"));
    assertEquals(70L, points.get("test:long-gauge-observer" + WITH_ATTRS));
    assertEquals(8.9, points.get("test:double-gauge-observer"));
    assertEquals(80.9, points.get("test:double-gauge-observer" + WITH_ATTRS));

    noopCallback.close();
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static class HistogramData {
    final double count;
    final List<Double> bucketBoundaries;
    final List<Double> bucketCounts;
    final double sum;

    HistogramData(
        double count, List<Double> bucketBoundaries, List<Double> bucketCounts, double sum) {
      this.count = count;
      this.bucketBoundaries = bucketBoundaries;
      this.bucketCounts = bucketCounts;
      this.sum = sum;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof HistogramData)) {
        return false;
      }
      HistogramData that = (HistogramData) o;
      return Double.compare(count, that.count) == 0
          && bucketBoundaries.equals(that.bucketBoundaries)
          && bucketCounts.equals(that.bucketCounts)
          && Double.compare(sum, that.sum) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(count, bucketBoundaries, bucketCounts, sum);
    }

    @Override
    public String toString() {
      return "HistogramData{"
          + "count="
          + count
          + ", bucketBoundaries="
          + bucketBoundaries
          + ", bucketCounts="
          + bucketCounts
          + ", sum="
          + sum
          + "}";
    }
  }

  static class MeterReader
      implements OtlpMetricsVisitor, OtlpScopedMetricsVisitor, OtlpMetricVisitor {
    private final Map<String, Object> points;
    private String scopeName;
    private String instrumentName;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    MeterReader(Map<String, Object> points) {
      this.points = points;
    }

    @Override
    public OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope) {
      scopeName = scope.getName().toString();
      return this;
    }

    @Override
    public OtlpMetricVisitor visitMetric(OtelInstrumentDescriptor descriptor) {
      instrumentName = descriptor.getName().toString();
      return this;
    }

    @Override
    public void visitAttribute(int type, String key, Object value) {
      attributes.put(key, value);
    }

    @Override
    public void visitDataPoint(OtlpDataPoint point) {
      String key = scopeName + ":" + instrumentName;
      if (!attributes.isEmpty()) {
        key = key + "@" + attributes;
        attributes.clear();
      }
      if (point instanceof OtlpLongPoint) {
        points.put(key, ((OtlpLongPoint) point).value);
      } else if (point instanceof OtlpDoublePoint) {
        points.put(key, ((OtlpDoublePoint) point).value);
      } else if (point instanceof OtlpHistogramPoint) {
        OtlpHistogramPoint h = (OtlpHistogramPoint) point;
        points.put(key, new HistogramData(h.count, h.bucketBoundaries, h.bucketCounts, h.sum));
      }
    }
  }
}
