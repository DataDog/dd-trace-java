package datadog.trace.api.iast.telemetry;

import static java.util.Collections.emptyList;

import java.util.Collection;

public class NoOpTelemetryCollector implements IastTelemetryCollector {

  @Override
  public boolean addMetric(final IastMetric metric, final long value, final String tag) {
    return true;
  }

  @Override
  public boolean merge(Collection<MetricData> metrics) {
    return true;
  }

  @Override
  public Collection<MetricData> drainMetrics() {
    return emptyList();
  }
}
