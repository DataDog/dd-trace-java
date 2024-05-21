package datadog.trace.instrumentation.opentelemetry14.trace;

import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.SPAN_KIND_INTERNAL;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelTracer implements Tracer {
  private static final String INSTRUMENTATION_NAME = "otel";
  private final AgentTracer.TracerAPI tracer;
  private final String instrumentationScopeName;

  public OtelTracer(String instrumentationScopeName) {
    this.instrumentationScopeName = instrumentationScopeName;
    this.tracer = AgentTracer.get();
  }
  public OtelTracer(String instrumentationScopeName, AgentTracer.TracerAPI tracer) {
    this.instrumentationScopeName = instrumentationScopeName;
    this.tracer = tracer;
  }

  @Override
  public SpanBuilder spanBuilder(String spanName) {
    AgentTracer.SpanBuilder delegate =
        this.tracer.buildSpan(INSTRUMENTATION_NAME, SPAN_KIND_INTERNAL).withResourceName(spanName);
    return new OtelSpanBuilder(delegate);
  }
}
