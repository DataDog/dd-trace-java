package datadog.opentelemetry;

import datadog.opentelemetry.trace.DDTracerProvider;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;

public class DDOpenTelemetry implements OpenTelemetry {
  @Override
  public TracerProvider getTracerProvider() {
    return new DDTracerProvider();
  }

  @Override
  public ContextPropagators getPropagators() {
    return ContextPropagators.noop(); // TODO
  }
}
