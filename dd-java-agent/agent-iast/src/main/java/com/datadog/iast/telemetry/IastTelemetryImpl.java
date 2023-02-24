package com.datadog.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;
import static datadog.trace.api.iast.telemetry.IastMetricHandler.conflated;
import static datadog.trace.api.iast.telemetry.IastMetricHandler.delegating;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricHandler;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.HasTelemetryCollector;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.MetricData;
import datadog.trace.api.iast.telemetry.IastTelemetryCollectorImpl;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class IastTelemetryImpl implements IastTelemetry {

  private static final String TRACE_METRIC_PATTERN = "dd.instrumentation_telemetry_data.iast.%s";

  private final Verbosity verbosity;

  public IastTelemetryImpl(final Verbosity verbosity) {
    this.verbosity = verbosity;
  }

  @Override
  public IastRequestContext onRequestStarted() {
    final TaintedObjects taintedObjects = buildTaintedObjects(verbosity);
    final IastTelemetryCollector collector = buildTelemetryCollector();
    return new RequestContextWithTelemetry(taintedObjects, collector);
  }

  @Override
  public void onRequestEnded(final IastRequestContext context, final TraceSegment trace) {
    if (context instanceof HasTelemetryCollector) {
      final IastTelemetryCollector collector =
          ((HasTelemetryCollector) context).getTelemetryCollector();
      final Collection<MetricData> metrics = collector.drainMetrics();
      if (!metrics.isEmpty()) {
        addMetricsToTrace(trace, metrics);
        IastTelemetryCollector.Holder.GLOBAL.merge(metrics);
      }
    }
  }

  private static void addMetricsToTrace(
      final TraceSegment trace, final Collection<MetricData> metrics) {
    final Map<IastMetric, Long> flatten =
        metrics.stream()
            .filter(data -> data.getMetric().getScope() == REQUEST)
            .collect(
                Collectors.groupingBy(
                    MetricData::getMetric,
                    Collectors.reducing(0L, IastTelemetryImpl::flatten, Long::sum)));
    for (final Map.Entry<IastMetric, Long> entry : flatten.entrySet()) {
      final String tagName = String.format(TRACE_METRIC_PATTERN, entry.getKey().getName());
      trace.setTagTop(tagName, entry.getValue());
    }
  }

  private static long flatten(final MetricData metric) {
    return metric.getPoints().stream().mapToLong(IastTelemetryCollector.Point::getValue).sum();
  }

  private static TaintedObjects buildTaintedObjects(final Verbosity verbosity) {
    return TaintedObjectsWithTelemetry.build(verbosity, TaintedObjects.acquire());
  }

  private static IastTelemetryCollector buildTelemetryCollector() {
    return new IastTelemetryCollectorImpl(IastTelemetryImpl::requestHandler);
  }

  /** Single point for request scoped metrics and delegate to global for global ones */
  private static IastMetricHandler requestHandler(final IastMetric metric) {
    return metric.getScope() == REQUEST
        ? conflated(metric)
        : delegating(metric, IastTelemetryCollector.Holder.GLOBAL);
  }
}
