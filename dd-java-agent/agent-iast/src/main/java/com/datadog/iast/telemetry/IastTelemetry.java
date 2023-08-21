package com.datadog.iast.telemetry;

import com.datadog.iast.IastRequestContext;
import datadog.trace.api.Config;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.internal.TraceSegment;
import javax.annotation.Nonnull;

public interface IastTelemetry {

  String TRACE_METRIC_PATTERN = "_dd.iast.telemetry.%s";

  IastRequestContext onRequestStarted();

  void onRequestEnded(IastRequestContext context, TraceSegment trace);

  static IastTelemetry build(@Nonnull final Config config) {
    final Verbosity verbosity = config.getIastTelemetryVerbosity();
    if (!config.isTelemetryEnabled() || verbosity == Verbosity.OFF) {
      return new NoOpTelemetry();
    }
    return new IastTelemetryImpl(verbosity);
  }
}
