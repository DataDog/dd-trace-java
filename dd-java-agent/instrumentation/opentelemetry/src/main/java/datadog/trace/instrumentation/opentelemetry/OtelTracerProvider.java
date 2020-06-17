package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.trace.Tracer;
import io.opentelemetry.trace.TracerProvider;

public class OtelTracerProvider implements TracerProvider {
  public static final OtelTracerProvider INSTANCE = new OtelTracerProvider();

  private final TypeConverter converter = new TypeConverter();

  @Override
  public Tracer get(final String instrumentationName) {
    return get(instrumentationName, null);
  }

  @Override
  public Tracer get(final String instrumentationName, final String instrumentationVersion) {
    return new OtelTracer(instrumentationName, AgentTracer.get(), converter);
  }
}
