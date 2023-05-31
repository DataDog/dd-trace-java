package datadog.telemetry.metric;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class MetricPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public final void doIteration(final TelemetryService service) {
    final Collection<MetricCollector.Metric> rawMetrics = collector().drain();
    // Convert raw metrics to telemetry metrics and join points of common metrics
    toTelemetryMetrics(rawMetrics).forEach(service::addMetric);
  }

  @NonNull
  public abstract MetricCollector<MetricCollector.Metric> collector();

  private Stream<Metric> toTelemetryMetrics(final Collection<MetricCollector.Metric> metrics) {
    final Map<MetricCollector.Metric, List<MetricCollector.Metric>> grouped =
        metrics.stream().collect(Collectors.groupingBy(Function.identity()));
    return grouped.values().stream().map(this::join);
  }

  private Metric join(final List<MetricCollector.Metric> list) {
    final MetricCollector.Metric raw = list.get(0);
    final Metric result =
        new Metric()
            .namespace(raw.namespace)
            .metric(raw.metricName)
            .type(Metric.TypeEnum.COUNT)
            .common(raw.common)
            .tags(raw.tags);
    list.forEach(entry -> result.addPointsItem(Arrays.asList(entry.timestamp, entry.counter)));
    return result;
  }
}
