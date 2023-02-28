package datadog.trace.api.iast.telemetry;

import static java.util.Collections.emptyList;

import java.util.Collection;

public class NoOpTelemetryCollector implements IastTelemetryCollector {

  @Override
  public void addMetric(final IastMetric metric, final long value, final String tag) {}

  @Override
  public void merge(Collection<MetricData> metrics) {}

  @Override
  public Collection<MetricData> drainMetrics() {
    return emptyList();
  }
}
