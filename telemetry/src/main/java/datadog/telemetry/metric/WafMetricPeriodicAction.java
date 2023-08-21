package datadog.telemetry.metric;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.WafMetricCollector;
import datadog.trace.api.WafMetricCollector.WafMetric;
import java.util.Arrays;
import java.util.Collection;

public class WafMetricPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  @Override
  public void doIteration(final TelemetryService service) {
    // Convert raw metrics to telemetry metrics
    final Collection<WafMetric> rawMetrics = WafMetricCollector.get().drain();
    for (final WafMetric raw : rawMetrics) {
      final Metric metric =
          new Metric()
              .namespace(raw.namespace)
              .metric(raw.metricName)
              .type(Metric.TypeEnum.COUNT)
              .common(true)
              .tags(raw.tags)
              .addPointsItem(Arrays.asList(raw.timestamp, raw.counter));
      service.addMetric(metric);
    }
  }
}
