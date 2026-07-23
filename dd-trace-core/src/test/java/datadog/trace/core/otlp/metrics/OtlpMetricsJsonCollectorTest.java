package datadog.trace.core.otlp.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.json.JsonMapper;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtlpMetricsJsonCollector}, parsing the produced JSON back with {@link
 * JsonMapper} to verify the OTLP JSON encoding: lowerCamelCase keys, integer aggregation
 * temporality, decimal-string timestamps/counts, and the overflow-bucket handling for histograms.
 */
class OtlpMetricsJsonCollectorTest {

  private static final long START_EPOCH_NS = TimeUnit.SECONDS.toNanos(1330837567);
  private static final long END_EPOCH_NS = START_EPOCH_NS + TimeUnit.MINUTES.toNanos(30);

  @Test
  void emptyRegistryProducesEmptyPayload() {
    OtlpMetricsJsonCollector collector = new OtlpMetricsJsonCollector(new ControllableTimeSource());
    OtlpPayload payload = collector.collectMetrics(visitor -> {});
    assertEquals(OtlpPayload.EMPTY, payload);
  }

  @Test
  void metricWithNoDataPointsContributesNothing() throws IOException {
    OtelInstrumentDescriptor descriptor =
        new OtelInstrumentDescriptor("unused", COUNTER, true, null, null);

    OtlpPayload payload =
        collect(
            visitor -> {
              OtlpScopedMetricsVisitor scoped =
                  visitor.visitScopedMetrics(new OtelInstrumentationScope("io.test", null, null));
              scoped.visitMetric(descriptor); // never visited with an attribute or data point
            });

    assertEquals(OtlpPayload.EMPTY, payload);
  }

  @Test
  void scopeWithNoDataPointsContributesNothingWhenAnotherScopeHasData() throws IOException {
    OtelInstrumentDescriptor emptyDescriptor =
        new OtelInstrumentDescriptor("unused", COUNTER, true, null, null);

    OtlpPayload payload =
        collect(
            visitor -> {
              OtlpScopedMetricsVisitor emptyScope =
                  visitor.visitScopedMetrics(new OtelInstrumentationScope("io.empty", null, null));
              emptyScope.visitMetric(emptyDescriptor); // never visited with a data point

              OtlpScopedMetricsVisitor dataScope =
                  visitor.visitScopedMetrics(new OtelInstrumentationScope("io.data", null, null));
              OtlpMetricVisitor mv =
                  dataScope.visitMetric(
                      new OtelInstrumentDescriptor("requests", COUNTER, true, null, null));
              mv.visitDataPoint(new OtlpLongPoint(1L));
            });

    List<Object> scopeMetrics = onlyScopeMetrics(payload);
    assertEquals(1, scopeMetrics.size(), "the empty scope must not appear in the payload");

    Map<String, Object> scopeMetric = (Map<String, Object>) scopeMetrics.get(0);
    Map<String, Object> scope = (Map<String, Object>) scopeMetric.get("scope");
    assertEquals("io.data", scope.get("name"));
  }

  @Test
  void gaugeHasNoStartTimeOrTemporality() throws IOException {
    Map<String, Object> metric =
        onlyMetric(
            collect(
                visitor -> {
                  OtlpScopedMetricsVisitor scoped =
                      visitor.visitScopedMetrics(
                          new OtelInstrumentationScope("io.gauge", null, null));
                  OtlpMetricVisitor mv =
                      scoped.visitMetric(
                          new OtelInstrumentDescriptor("connections", GAUGE, false, null, null));
                  mv.visitDataPoint(new OtlpDoublePoint(5.0));
                }));

    assertEquals("connections", metric.get("name"));
    Map<String, Object> gauge = (Map<String, Object>) metric.get("gauge");
    List<Object> dataPoints = (List<Object>) gauge.get("dataPoints");
    assertEquals(1, dataPoints.size());
    Map<String, Object> point = (Map<String, Object>) dataPoints.get(0);
    assertFalse(point.containsKey("startTimeUnixNano"), "gauges have no start time");
    assertEquals(Long.toString(END_EPOCH_NS), point.get("timeUnixNano"));
    assertEquals(5.0, ((Number) point.get("asDouble")).doubleValue());
  }

  @Test
  void counterIsMonotonicSumWithIntegerTemporalityAndDecimalStringValue() throws IOException {
    Map<String, Object> metric =
        onlyMetric(
            collect(
                visitor -> {
                  OtlpScopedMetricsVisitor scoped =
                      visitor.visitScopedMetrics(
                          new OtelInstrumentationScope("io.test", null, null));
                  OtlpMetricVisitor mv =
                      scoped.visitMetric(
                          new OtelInstrumentDescriptor("requests", COUNTER, true, null, null));
                  mv.visitAttribute(STRING_ATTRIBUTE, "method", "GET");
                  mv.visitDataPoint(new OtlpLongPoint(42L));
                }));

    Map<String, Object> sum = (Map<String, Object>) metric.get("sum");
    assertTrue(((Number) sum.get("aggregationTemporality")).intValue() >= 1);
    assertEquals(Boolean.TRUE, sum.get("isMonotonic"));

    List<Object> dataPoints = (List<Object>) sum.get("dataPoints");
    Map<String, Object> point = (Map<String, Object>) dataPoints.get(0);
    assertEquals(Long.toString(START_EPOCH_NS), point.get("startTimeUnixNano"));
    assertEquals(Long.toString(END_EPOCH_NS), point.get("timeUnixNano"));
    assertEquals("42", point.get("asInt"));

    List<Object> attributes = (List<Object>) point.get("attributes");
    Map<String, Object> attr = (Map<String, Object>) attributes.get(0);
    assertEquals("method", attr.get("key"));
  }

  @Test
  void histogramWithOverflowBoundaryOmitsInfinityAndAppendsNoExtraZero() throws IOException {
    Map<String, Object> metric =
        onlyMetric(
            collect(
                visitor -> {
                  OtlpScopedMetricsVisitor scoped =
                      visitor.visitScopedMetrics(
                          new OtelInstrumentationScope("io.hist", null, null));
                  OtlpMetricVisitor mv =
                      scoped.visitMetric(
                          new OtelInstrumentDescriptor(
                              "request.size", HISTOGRAM, false, null, null));
                  mv.visitDataPoint(
                      new OtlpHistogramPoint(
                          5.0,
                          Arrays.asList(100.0, Double.POSITIVE_INFINITY),
                          Arrays.asList(4.0, 1.0),
                          280.0,
                          20.0,
                          200.0));
                }));

    Map<String, Object> histogram = (Map<String, Object>) metric.get("histogram");
    List<Object> dataPoints = (List<Object>) histogram.get("dataPoints");
    Map<String, Object> point = (Map<String, Object>) dataPoints.get(0);

    assertEquals("5", point.get("count"));
    assertEquals(280.0, ((Number) point.get("sum")).doubleValue());
    assertEquals(20.0, ((Number) point.get("min")).doubleValue());
    assertEquals(200.0, ((Number) point.get("max")).doubleValue());

    List<Object> bounds = (List<Object>) point.get("explicitBounds");
    assertEquals(Arrays.asList(100.0), bounds);

    List<Object> counts = (List<Object>) point.get("bucketCounts");
    assertEquals(Arrays.asList("4", "1"), counts);
  }

  @Test
  void histogramWithoutOverflowBoundaryAppendsExtraZeroCount() throws IOException {
    Map<String, Object> metric =
        onlyMetric(
            collect(
                visitor -> {
                  OtlpScopedMetricsVisitor scoped =
                      visitor.visitScopedMetrics(
                          new OtelInstrumentationScope("io.hist", null, null));
                  OtlpMetricVisitor mv =
                      scoped.visitMetric(
                          new OtelInstrumentDescriptor("queue.size", HISTOGRAM, false, null, null));
                  mv.visitDataPoint(
                      new OtlpHistogramPoint(
                          8.0,
                          Arrays.asList(50.0, 100.0),
                          Arrays.asList(3.0, 5.0),
                          750.0,
                          10.0,
                          95.0));
                }));

    Map<String, Object> histogram = (Map<String, Object>) metric.get("histogram");
    Map<String, Object> point =
        (Map<String, Object>) ((List<Object>) histogram.get("dataPoints")).get(0);

    List<Object> bounds = (List<Object>) point.get("explicitBounds");
    assertEquals(Arrays.asList(50.0, 100.0), bounds);

    List<Object> counts = (List<Object>) point.get("bucketCounts");
    assertEquals(Arrays.asList("3", "5", "0"), counts, "extra zero count appended, no overflow");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static OtlpPayload collect(Consumer<OtlpMetricsVisitor> body) {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(START_EPOCH_NS); // captured in constructor
    OtlpMetricsJsonCollector collector = new OtlpMetricsJsonCollector(timeSource);
    timeSource.set(END_EPOCH_NS); // captured during collection
    return collector.collectMetrics(body::accept);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyMetric(OtlpPayload payload) throws IOException {
    List<Object> scopeMetrics = onlyScopeMetrics(payload);
    Map<String, Object> scopeMetric = (Map<String, Object>) scopeMetrics.get(0);
    List<Object> metrics = (List<Object>) scopeMetric.get("metrics");
    assertEquals(1, metrics.size());
    return (Map<String, Object>) metrics.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> onlyScopeMetrics(OtlpPayload payload) throws IOException {
    byte[] bytes = new byte[payload.getContentLength()];
    payload.getContent().get(bytes);
    String json = new String(bytes, StandardCharsets.UTF_8);
    Map<String, Object> root = JsonMapper.fromJsonToMap(json);

    List<Object> resourceMetrics = (List<Object>) root.get("resourceMetrics");
    Map<String, Object> resourceMetric = (Map<String, Object>) resourceMetrics.get(0);
    return (List<Object>) resourceMetric.get("scopeMetrics");
  }
}
