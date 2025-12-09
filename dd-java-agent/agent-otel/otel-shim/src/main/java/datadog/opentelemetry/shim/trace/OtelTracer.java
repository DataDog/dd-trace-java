package datadog.opentelemetry.shim.trace;

import static datadog.opentelemetry.shim.trace.OtelConventions.SPAN_KIND_INTERNAL;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelTracer implements Tracer {
  private static final String INSTRUMENTATION_NAME = otelInstrumentationName();

  private final AgentTracer.TracerAPI tracer;

  OtelTracer(@SuppressWarnings("unused") String instrumentationScopeName) {
    this.tracer = AgentTracer.get();
  }

  @Override
  public SpanBuilder spanBuilder(String spanName) {
    AgentTracer.SpanBuilder delegate =
        this.tracer.buildSpan(INSTRUMENTATION_NAME, SPAN_KIND_INTERNAL).withResourceName(spanName);
    return new OtelSpanBuilder(delegate);
  }

  @SuppressWarnings("ConstantConditions")
  private static String otelInstrumentationName() {
    // is this the bootstrap shim for drop-in support, or the shim for manual instrumentation?
    return OtelTracer.class.getName().startsWith("datadog.trace.bootstrap")
        ? "otel.library"
        : "otel";
  }
}
