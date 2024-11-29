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
import org.junit.runners.Parameterized;

public class JUnit4BeforeAfterOperationsTracer {
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
    } else if (method.isAnnotationPresent(Parameterized.BeforeParam.class)) {
      span.setTag(Tags.TEST_CALLBACK, "BeforeParam");
    } else if (method.isAnnotationPresent(Parameterized.AfterParam.class)) {
      span.setTag(Tags.TEST_CALLBACK, "AfterParam");
    }
    return AgentTracer.activateSpan(span);
  }

  public static void endTrace(final AgentScope scope, final Throwable throwable) {
    final AgentSpan span = scope.span();
    if (throwable != null) {
      span.addThrowable(throwable);
    }
    scope.close();
    span.finish();
  }
}
