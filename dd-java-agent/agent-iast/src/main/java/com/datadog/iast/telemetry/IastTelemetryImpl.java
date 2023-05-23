package com.datadog.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasTelemetryCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.IastMetricData;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;

public class IastTelemetryImpl implements IastTelemetry {

  private final Verbosity verbosity;

  public IastTelemetryImpl(final Verbosity verbosity) {
    this.verbosity = verbosity;
  }

  @Override
  public IastRequestContext onRequestStarted() {
    final TaintedObjects taintedObjects = buildTaintedObjects(verbosity);
    // enable only request scoped metrics
    final IastMetricCollector collector = buildTelemetryCollector();
    return new RequestContextWithTelemetry(taintedObjects, collector);
  }

  @Override
  public void onRequestEnded(final IastRequestContext context, final TraceSegment trace) {
    if (context instanceof HasTelemetryCollector) {
      final IastMetricCollector collector =
          ((HasTelemetryCollector) context).getTelemetryCollector();
      collector.prepareMetrics();
      final Collection<IastMetricData> metrics = collector.drain();
      if (!metrics.isEmpty()) {
        addMetricsToTrace(trace, metrics);
        addMetricsToTelemetry(metrics);
      }
    }
  }

  private static void addMetricsToTrace(
      final TraceSegment trace, final Collection<IastMetricData> metrics) {
    for (final IastMetricData data : metrics) {
      final IastMetric metric = data.metric;
      if (metric.getScope() == REQUEST) {
        final String tagValue = metric.getSpanTag();
        trace.setTagTop(String.format(TRACE_METRIC_PATTERN, tagValue), data.counter);
      }
    }
  }

  private static void addMetricsToTelemetry(final Collection<IastMetricData> metrics) {
    IastMetricCollector.get().merge(metrics);
  }

  private static TaintedObjects buildTaintedObjects(final Verbosity verbosity) {
    return TaintedObjectsWithTelemetry.build(verbosity, TaintedObjects.acquire());
  }

  private static IastMetricCollector buildTelemetryCollector() {
    return new IastMetricCollector();
  }
}
