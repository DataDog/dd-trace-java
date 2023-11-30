package datadog.telemetry.metric;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public abstract class MetricPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  @Override
  public final void doIteration(final TelemetryService service) {
    final Collection<MetricCollector.Metric> rawMetrics = collector().drain();
    // Convert raw metrics to telemetry metrics and join points of common metrics
    toTelemetryMetrics(rawMetrics).forEach(service::addMetric);
  }

  @NonNull
  public abstract MetricCollector<MetricCollector.Metric> collector();

  private Collection<Metric> toTelemetryMetrics(final Collection<MetricCollector.Metric> metrics) {
    // Use an intermediate map to quickly find telemetry metric related to a metric
    Map<MetricCollector.Metric, Metric> telemetryMetrics = new HashMap<>();
    // Aggregate equal metrics into a single telemetry metric with multiple points
    for (MetricCollector.Metric metric : metrics) {
      Metric telemetryMetric =
          telemetryMetrics.computeIfAbsent(metric, this::convertToTelemetryMetric);
      ArrayList<Number> point = new ArrayList<>(2);
      point.add(metric.timestamp);
      point.add(metric.value);
      telemetryMetric.addPointsItem(point);
    }
    return telemetryMetrics.values();
  }

  private Metric convertToTelemetryMetric(MetricCollector.Metric raw) {
    return new Metric()
        .namespace(raw.namespace)
        .metric(raw.metricName)
        .type(typeFromValue(raw.type))
        .common(raw.common)
        .tags(raw.tags);
  }

  private static Metric.TypeEnum typeFromValue(String value) {
    for (Metric.TypeEnum typeEnum : Metric.TypeEnum.values()) {
      if (typeEnum.value().equals(value)) {
        return typeEnum;
      }
    }
    throw new IllegalArgumentException("Invalid metric type value: " + value);
  }
}
