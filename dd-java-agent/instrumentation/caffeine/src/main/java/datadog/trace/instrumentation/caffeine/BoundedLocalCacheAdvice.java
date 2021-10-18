package datadog.trace.instrumentation.caffeine;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

public class BoundedLocalCacheAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter() {
    return activateSpan(noopSpan());
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(@Advice.Enter final AgentScope scope) {
    scope.close();
  }
}
