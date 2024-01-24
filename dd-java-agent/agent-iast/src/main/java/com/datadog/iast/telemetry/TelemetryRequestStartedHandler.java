package com.datadog.iast.telemetry;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.RequestStartedHandler;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry;
import datadog.trace.api.Config;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import javax.annotation.Nonnull;

public class TelemetryRequestStartedHandler extends RequestStartedHandler {

  public TelemetryRequestStartedHandler(@Nonnull final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  protected IastRequestContext newContext() {
    final IastRequestContext ctx = super.newContext();
    final Config config = Config.get();
    final Verbosity verbosity = config.getIastTelemetryVerbosity();
    final TaintedObjects withTelemetry = TaintedObjectsWithTelemetry.build(verbosity, ctx);
    ctx.setTaintedObjects(withTelemetry);
    ctx.setCollector(new IastMetricCollector());
    return ctx;
  }
}
