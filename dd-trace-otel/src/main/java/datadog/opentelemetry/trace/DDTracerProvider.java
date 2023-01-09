package datadog.opentelemetry.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;

public class DDTracerProvider implements TracerProvider {
  @Override
  public Tracer get(String instrumentationScopeName) {
    return null;
  }

  @Override
  public Tracer get(String instrumentationScopeName, String instrumentationScopeVersion) {
    return null;
  }

  //  @Override
  //  public TracerBuilder tracerBuilder(String instrumentationScopeName) {
  //    return TracerProvider.super.tracerBuilder(instrumentationScopeName);
  //  }
}
