package datadog.opentelemetry.trace;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
class DDTracer implements Tracer {
  private final AgentTracer.TracerAPI tracer;

  public DDTracer(String instrumentationScopeName) {
    CoreTracer.CoreTracerBuilder builder = CoreTracer.builder();
    builder.serviceName(instrumentationScopeName);
    this.tracer = builder.build();
  }

  @Override
  public SpanBuilder spanBuilder(String spanName) {
    AgentTracer.SpanBuilder delegate = this.tracer.buildSpan(spanName);
    return new DDSpanBuilder(delegate);
  }
}
