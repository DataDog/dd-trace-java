package datadog.opentelemetry.trace;

import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;

public class DDTracer implements Tracer {
  @Override
  public SpanBuilder spanBuilder(String spanName) {
    return null;
  }
}
