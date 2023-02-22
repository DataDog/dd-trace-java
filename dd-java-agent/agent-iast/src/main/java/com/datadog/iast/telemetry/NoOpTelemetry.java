package com.datadog.iast.telemetry;

import com.datadog.iast.IastRequestContext;
import datadog.trace.api.internal.TraceSegment;

public class NoOpTelemetry implements IastTelemetry {

  @Override
  public IastRequestContext onRequestStarted() {
    return new IastRequestContext();
  }

  @Override
  public void onRequestEnded(final IastRequestContext context, final TraceSegment trace) {}
}
