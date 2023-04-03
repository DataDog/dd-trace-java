package com.datadog.iast.telemetry;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.HasTelemetryCollector;

public class RequestContextWithTelemetry extends IastRequestContext
    implements HasTelemetryCollector {

  private final IastTelemetryCollector collector;

  public RequestContextWithTelemetry(
      final TaintedObjects taintedObjects, final IastTelemetryCollector collector) {
    super(taintedObjects);
    this.collector = collector;
  }

  @Override
  public IastTelemetryCollector getTelemetryCollector() {
    return collector;
  }
}
