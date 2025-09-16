package datadog.telemetry.rum;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Metric;
import datadog.trace.api.rum.RumTelemetryCollector;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.Arrays;
import java.util.Collection;

/** RUM version of IntegrationPeriodicAction that sends RUM telemetry metrics. */
public class RumPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  private final RumTelemetryCollector telemetryCollector;

  public RumPeriodicAction(RumTelemetryCollector telemetryCollector) {
    this.telemetryCollector = telemetryCollector;
  }

  @Override
  public void doIteration(TelemetryService service) {
    Collection<MetricCollector.Metric> counts = telemetryCollector.drain();
    for (MetricCollector.Metric metric : counts) {
      Metric telemetryMetric = convertToTelemetryMetric(metric);
      service.addMetric(telemetryMetric);
    }

    Collection<MetricCollector.DistributionSeriesPoint> distributions =
        telemetryCollector.drainDistributionSeries();
    for (MetricCollector.DistributionSeriesPoint distribution : distributions) {
      DistributionSeries telemetryDistribution = convertToDistributionSeries(distribution);
      service.addDistributionSeries(telemetryDistribution);
    }
  }

  private Metric convertToTelemetryMetric(MetricCollector.Metric raw) {
    return new Metric()
        .namespace(raw.namespace)
        .metric(raw.metricName)
        .type(Metric.TypeEnum.COUNT)
        .common(raw.common)
        .tags(raw.tags)
        .addPointsItem(Arrays.asList(raw.timestamp, raw.value));
  }

  private DistributionSeries convertToDistributionSeries(
      MetricCollector.DistributionSeriesPoint point) {
    DistributionSeries distribution =
        new DistributionSeries()
            .namespace(point.namespace)
            .metric(point.metricName)
            .common(point.common)
            .tags(point.tags);
    distribution.addPoint(point.value);
    return distribution;
  }
}
