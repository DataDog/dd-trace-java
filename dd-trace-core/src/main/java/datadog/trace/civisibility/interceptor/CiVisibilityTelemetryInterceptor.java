package datadog.trace.civisibility.interceptor;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Collection;

public class CiVisibilityTelemetryInterceptor extends AbstractTraceInterceptor {

  public CiVisibilityTelemetryInterceptor() {
    super(Priority.CI_VISIBILITY_TELEMETRY);
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityCountMetric.EVENTS_ENQUEUED_FOR_SERIALIZATION, trace.size());
    return trace;
  }
}
