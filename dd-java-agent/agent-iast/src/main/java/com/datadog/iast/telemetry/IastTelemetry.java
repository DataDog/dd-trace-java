package com.datadog.iast.telemetry;

import com.datadog.iast.IastRequestContext;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.internal.TraceSegment;
import javax.annotation.Nonnull;

public interface IastTelemetry {

  IastRequestContext onRequestStarted();

  void onRequestEnded(IastRequestContext context, TraceSegment trace);

  static IastTelemetry build(@Nonnull final Verbosity verbosity) {
    if (verbosity == Verbosity.OFF) {
      return new NoOpTelemetry();
    }
    return new IastTelemetryImpl(verbosity);
  }
}
