package datadog.telemetry.metric;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.MetricCollector;
import datadog.trace.api.MetricCollector.RawMetric;
import datadog.trace.api.MetricCollector.WafInitRawMetric;
import datadog.trace.api.MetricCollector.WafRequestsRawMetric;
import datadog.trace.api.MetricCollector.WafUpdatesRawMetric;
import java.util.Arrays;
import java.util.Collection;

public class MetricPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  @Override
  public void doIteration(TelemetryService service) {
    Collection<RawMetric> rawMetrics = MetricCollector.get().drain();

    // Convert raw metrics to telemetry metrics
    for (MetricCollector.RawMetric raw : rawMetrics) {
      Metric metric =
          new Metric()
              .namespace(raw.namespace)
              .metric(raw.metricName)
              .type(Metric.TypeEnum.COUNT)
              .common(true)
              .addPointsItem(Arrays.asList(raw.timestamp, raw.counter));

      if (raw instanceof WafInitRawMetric) {
        metric.addTagsItem("waf_version:" + ((WafInitRawMetric) raw).wafVersion);
        metric.addTagsItem("event_rules_version:" + ((WafInitRawMetric) raw).rulesVersion);
      }
      if (raw instanceof WafUpdatesRawMetric) {
        metric.addTagsItem("event_rules_version:" + ((WafUpdatesRawMetric) raw).rulesVersion);
      }
      if (raw instanceof WafRequestsRawMetric) {
        metric.addTagsItem("triggered:" + ((WafRequestsRawMetric) raw).triggered);
        metric.addTagsItem("blocked:" + ((WafRequestsRawMetric) raw).blocked);
      }

      service.addMetric(metric);
    }
  }
}
