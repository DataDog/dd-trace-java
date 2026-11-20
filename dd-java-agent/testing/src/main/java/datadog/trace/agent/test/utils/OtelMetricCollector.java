package datadog.trace.agent.test.utils;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test fixture that collects the bootstrap {@link OtelMetricRegistry} into plain maps, so tests can
 * assert metric names, attributes and values. Callers need otel-bootstrap on the test classpath.
 */
public final class OtelMetricCollector
    implements OtlpMetricsVisitor, OtlpScopedMetricsVisitor, OtlpMetricVisitor {

  /** One collected data point; attribute values are their {@code toString()}. */
  public static final class DataPointEntry {
    public final Map<String, Object> attrs;

    /**
     * The value of a long or double point, or {@code null} for other point types (such as
     * histograms): read those from {@link #point}.
     */
    public final Number value;

    public final OtlpDataPoint point;

    DataPointEntry(Map<String, Object> attrs, Number value, OtlpDataPoint point) {
      this.attrs = attrs;
      this.value = value;
      this.point = point;
    }
  }

  /** Metric names in collection order, including metrics that reported no data points. */
  public final Set<String> metricNames = new LinkedHashSet<>();

  /** Data points by metric name. */
  public final Map<String, List<DataPointEntry>> points = new LinkedHashMap<>();

  private final Map<String, Object> currentAttrs = new LinkedHashMap<>();
  private String currentInstrument = "";

  private OtelMetricCollector() {}

  /** Collects the bootstrap registry once. */
  public static OtelMetricCollector collect() {
    OtelMetricCollector collector = new OtelMetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);
    return collector;
  }

  @Override
  public OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope) {
    return this;
  }

  @Override
  public OtlpMetricVisitor visitMetric(OtelInstrumentDescriptor descriptor) {
    currentInstrument = descriptor.getName().toString();
    metricNames.add(currentInstrument);
    points.computeIfAbsent(currentInstrument, k -> new ArrayList<>());
    return this;
  }

  @Override
  public void visitAttribute(int type, String key, Object value) {
    currentAttrs.put(key, value == null ? null : value.toString());
  }

  @Override
  public void visitDataPoint(OtlpDataPoint point) {
    Map<String, Object> attrs = new HashMap<>(currentAttrs);
    currentAttrs.clear();
    Number value = null;
    if (point instanceof OtlpLongPoint) {
      value = ((OtlpLongPoint) point).value;
    } else if (point instanceof OtlpDoublePoint) {
      value = ((OtlpDoublePoint) point).value;
    }
    points
        .computeIfAbsent(currentInstrument, k -> new ArrayList<>())
        .add(new DataPointEntry(attrs, value, point));
  }

  /** Distinct values of one attribute across the given metric's data points. */
  public Set<String> attributeValues(String metricName, String attrKey) {
    return points.getOrDefault(metricName, Collections.emptyList()).stream()
        .map(e -> e.attrs.get(attrKey))
        .filter(Objects::nonNull)
        .map(Object::toString)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
