package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class ActiveResilience4jSpan {
  public static final CharSequence SPAN_NAME = UTF8BytesString.create("resilience4j");
  public static final String INSTRUMENTATION_NAME = "resilience4j";

  public static AgentSpan activeSpan() {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null || !SPAN_NAME.equals(span.getOperationName())) {
      return null;
    }
    return span;
  }

  public static AgentSpan startSpan() {
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
    // TODO decorate
    return span;
  }

  public static void finishScope(AgentScope scope) {
    if (scope != null) {
      // TODO decorate
      scope.close();
    }
  }

  public static void finishSpan(AgentSpan span) {
    if (span != null) {
      // TODO decorate
      span.finish();
    }
  }
}
