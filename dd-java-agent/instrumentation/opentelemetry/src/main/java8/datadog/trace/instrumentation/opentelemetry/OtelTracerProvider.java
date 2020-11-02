package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;

public final class OtelTracerProvider implements TracerProvider {

  private final TypeConverter converter;

  public OtelTracerProvider(ContextStore<SpanContext, AgentSpan.Context> spanContextStore) {
    converter = new TypeConverter(spanContextStore);
  }

  @Override
  public Tracer get(final String instrumentationName) {
    return get(instrumentationName, null);
  }

  @Override
  public Tracer get(final String instrumentationName, final String instrumentationVersion) {
    // TODO: cache return value.
    return new OtelTracer(instrumentationName, AgentTracer.get(), converter);
  }
}
