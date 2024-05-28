package com.datadog.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.IastMetricData;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class TelemetryRequestEndedHandler
    implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

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
    final IastContext iastCtx = context.getData(RequestContextSlot.IAST);
    if (!(iastCtx instanceof HasMetricCollector)) {
      return;
    }
    final IastMetricCollector collector = ((HasMetricCollector) iastCtx).getMetricCollector();
    if (collector == null) {
      return;
    }
    collector.prepareMetrics();
    final Collection<IastMetricData> metrics = collector.drain();
    if (!metrics.isEmpty()) {
      final TraceSegment trace = context.getTraceSegment();
      addMetricsToTrace(trace, metrics);
      addMetricsToTelemetry(metrics);
    }
  }

  private static void addMetricsToTrace(
      final TraceSegment trace, final Collection<IastMetricData> metrics) {
    for (final IastMetricData data : metrics) {
      final IastMetric metric = data.getMetric();
      if (metric.getScope() == REQUEST) {
        trace.setTagTop(data.getSpanTag(), data.value);
      }
    }
  }

  private static void addMetricsToTelemetry(final Collection<IastMetricData> metrics) {
    IastMetricCollector.get().merge(metrics);
  }
}
