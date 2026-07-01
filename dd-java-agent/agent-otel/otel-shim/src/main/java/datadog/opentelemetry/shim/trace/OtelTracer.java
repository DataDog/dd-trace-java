package datadog.opentelemetry.shim.trace;

import static datadog.opentelemetry.shim.trace.OtelConventions.SPAN_KIND_INTERNAL;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelTracer implements Tracer {
  private static final String INSTRUMENTATION_NAME = otelInstrumentationName();

  private final OtelInstrumentationScope instrumentationScope;

  private final AgentTracer.TracerAPI tracer;

  OtelTracer(OtelInstrumentationScope instrumentationScope) {
    this.instrumentationScope = instrumentationScope;
    this.tracer = AgentTracer.get();
  }

  @Override
  public SpanBuilder spanBuilder(String spanName) {
    AgentTracer.SpanBuilder delegate =
        this.tracer.buildSpan(INSTRUMENTATION_NAME, SPAN_KIND_INTERNAL).withResourceName(spanName);
    return new OtelSpanBuilder(delegate);
  }

  @Override
  public String toString() {
    return "OtelTracer{instrumentationScope=" + instrumentationScope + "}";
  }

  @SuppressWarnings("ConstantConditions")
  private static String otelInstrumentationName() {
    // is this the bootstrap shim for drop-in support, or the shim for manual instrumentation?
    return OtelTracer.class.getName().startsWith("datadog.trace.bootstrap")
        ? "otel.library"
        : "otel";
  }
}
