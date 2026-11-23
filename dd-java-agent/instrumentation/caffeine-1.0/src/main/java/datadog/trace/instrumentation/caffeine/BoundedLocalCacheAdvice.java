package datadog.trace.instrumentation.caffeine;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.context.ContextScope;
import net.bytebuddy.asm.Advice;

public class BoundedLocalCacheAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope enter() {
    return activateSpan(noopSpan());
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(@Advice.Enter final ContextScope scope) {
    scope.close();
  }
}
