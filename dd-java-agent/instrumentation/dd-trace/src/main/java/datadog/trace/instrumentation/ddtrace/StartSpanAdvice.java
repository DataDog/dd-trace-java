package datadog.trace.instrumentation.ddtrace;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

public class StartSpanAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public AgentScope start() {
    if (CallDepthThreadLocalMap.getCallDepth(AgentTracer.class) > 0) {
      return null;
    }
    CallDepthThreadLocalMap.incrementCallDepth(AgentTracer.class);
    AgentScope scope = activeScope();
    if (scope != null) {
      scope.setAsyncPropagation(true);
    }
    // start a span
    final AgentSpan span = startSpan("startSpan");
    span.setResourceName("startSpan");
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(@Advice.Enter AgentScope scope) {
    AgentSpan span = scope.span();
    while (span != null) {
      span.finish();
      scope.close();
    }
    CallDepthThreadLocalMap.reset(AgentTracer.class);
  }
}
