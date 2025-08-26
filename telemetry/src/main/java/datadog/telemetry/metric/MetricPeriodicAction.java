package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Metric;
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

    Collection<MetricCollector.DistributionSeriesPoint> rawDistributionSeriesPoints =
        collector().drainDistributionSeries();
    toDistributionSeries(rawDistributionSeriesPoints).forEach(service::addDistributionSeries);
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

  private Collection<DistributionSeries> toDistributionSeries(
      Collection<MetricCollector.DistributionSeriesPoint> rawDistributionSeriesPoints) {
    Map<MetricCollector.DistributionSeriesPoint, DistributionSeries> distributionSeries =
        new HashMap<>();
    for (MetricCollector.DistributionSeriesPoint point : rawDistributionSeriesPoints) {
      distributionSeries
          .computeIfAbsent(point, this::convertToDistributionSeries)
          .addPoint(point.value);
    }
    return distributionSeries.values();
  }

  private DistributionSeries convertToDistributionSeries(
      MetricCollector.DistributionSeriesPoint point) {
    return new DistributionSeries()
        .namespace(point.namespace)
        .metric(point.metricName)
        .common(point.common)
        .tags(point.tags);
  }
}
