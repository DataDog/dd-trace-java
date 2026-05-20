package datadog.trace.instrumentation.resilience4j.common;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class Resilience4jSpan {
  public static final String INSTRUMENTATION_NAME = "resilience4j";
  public static final CharSequence SPAN_NAME = UTF8BytesString.create(INSTRUMENTATION_NAME);

  public static AgentSpan current() {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return null;
    }
    CharSequence operationName = span.getOperationName();
    if (operationName == null) {
      return null;
    }
    if (!SPAN_NAME.toString().equals(operationName.toString())) {
      return null;
    }
    return span;
  }

  public static AgentSpan start() {
    return AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
  }
}
