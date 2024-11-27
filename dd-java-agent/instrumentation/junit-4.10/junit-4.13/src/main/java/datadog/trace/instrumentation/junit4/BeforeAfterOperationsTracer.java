package datadog.trace.instrumentation.junit4;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

public class BeforeAfterOperationsTracer {
  public static AgentScope startTrace(final Method method) {
    final AgentSpan span = AgentTracer.startSpan("junit", method.getName());
    if (method.isAnnotationPresent(Before.class)) {
      span.setTag(Tags.TEST_CALLBACK, "Before");
    } else if (method.isAnnotationPresent(After.class)) {
      span.setTag(Tags.TEST_CALLBACK, "After");
    } else if (method.isAnnotationPresent(BeforeClass.class)) {
      span.setTag(Tags.TEST_CALLBACK, "BeforeClass");
    } else if (method.isAnnotationPresent(AfterClass.class)) {
      span.setTag(Tags.TEST_CALLBACK, "AfterClass");
    }
    return AgentTracer.activateSpan(span);
  }

  public static void endTrace(final AgentScope scope) {
    final AgentSpan span = scope.span();
    scope.close();
    span.finish();
  }
}
