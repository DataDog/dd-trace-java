package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class Resilience4jSpan {
  public static final String RESILIENCE4J = "resilience4j";
  public static final CharSequence SPAN_NAME = UTF8BytesString.create(RESILIENCE4J);

  public static AgentSpan current() {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null || !RESILIENCE4J.contentEquals(span.getOperationName())) {
      return null;
    }
    return span;
  }

  public static AgentSpan start() {
    return AgentTracer.startSpan(RESILIENCE4J, SPAN_NAME);
  }
}
