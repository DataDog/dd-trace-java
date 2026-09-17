package datadog.telemetry.metric;

import datadog.trace.api.debugger.DebuggerMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;

public class DebuggerMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  public MetricCollector collector() {
    return DebuggerMetricCollector.get();
  }
}
