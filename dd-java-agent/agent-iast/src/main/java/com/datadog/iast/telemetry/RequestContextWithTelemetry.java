package com.datadog.iast.telemetry;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasTelemetryCollector;

public class RequestContextWithTelemetry extends IastRequestContext
    implements HasTelemetryCollector {

  private final IastMetricCollector collector;

  public RequestContextWithTelemetry(
      final TaintedObjects taintedObjects, final IastMetricCollector collector) {
    super(taintedObjects);
    this.collector = collector;
  }

  @Override
  public IastMetricCollector getTelemetryCollector() {
    return collector;
  }
}
