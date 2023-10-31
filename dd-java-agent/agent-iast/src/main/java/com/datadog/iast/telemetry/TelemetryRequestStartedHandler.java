package com.datadog.iast.telemetry;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.RequestStartedHandler;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import javax.annotation.Nonnull;

public class TelemetryRequestStartedHandler extends RequestStartedHandler {

  public TelemetryRequestStartedHandler(@Nonnull final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  protected IastRequestContext newContext() {
    final IastMetricCollector collector = new IastMetricCollector();
    return new IastRequestContext(collector);
  }
}
