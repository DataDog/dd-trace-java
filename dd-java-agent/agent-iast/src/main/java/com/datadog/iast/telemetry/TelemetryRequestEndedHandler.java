package com.datadog.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;

import com.datadog.iast.IastRequestContext;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.IastMetricData;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class TelemetryRequestEndedHandler
    implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  static final String TRACE_METRIC_PATTERN = "_dd.iast.telemetry.%s";

  private final BiFunction<RequestContext, IGSpanInfo, Flow<Void>> delegate;

  public TelemetryRequestEndedHandler(
      @Nonnull final BiFunction<RequestContext, IGSpanInfo, Flow<Void>> delegate) {
    this.delegate = delegate;
  }

  @Override
  public Flow<Void> apply(final RequestContext context, final IGSpanInfo igSpanInfo) {
    final Flow<Void> result = delegate.apply(context, igSpanInfo);
    onRequestEnded(context);
    return result;
  }

  private static void onRequestEnded(final RequestContext context) {
    final IastRequestContext iastCtx = IastRequestContext.get(context);
    if (iastCtx != null && iastCtx.getMetricCollector() != null) {
      final IastMetricCollector collector = iastCtx.getMetricCollector();
      collector.prepareMetrics();
      final Collection<IastMetricData> metrics = collector.drain();
      if (!metrics.isEmpty()) {
        final TraceSegment trace = context.getTraceSegment();
        addMetricsToTrace(trace, metrics);
        addMetricsToTelemetry(metrics);
      }
    }
  }

  private static void addMetricsToTrace(
      final TraceSegment trace, final Collection<IastMetricData> metrics) {
    for (final IastMetricData data : metrics) {
      final IastMetric metric = data.getMetric();
      if (metric.getScope() == REQUEST) {
        final String tagValue = data.getSpanTag();
        trace.setTagTop(String.format(TRACE_METRIC_PATTERN, tagValue), data.value);
      }
    }
  }

  private static void addMetricsToTelemetry(final Collection<IastMetricData> metrics) {
    IastMetricCollector.get().merge(metrics);
  }
}
