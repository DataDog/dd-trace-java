package datadog.trace.agent.tooling.async;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;

import net.bytebuddy.asm.Advice;

/** Prevents background work from capturing the active scope's context. */
public class SuppressAsyncPropagationAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static boolean before() {
    if (isAsyncPropagationEnabled()) {
      setAsyncPropagationEnabled(false);
      return true;
    }
    return false;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void after(@Advice.Enter boolean wasDisabled) {
    if (wasDisabled) {
      setAsyncPropagationEnabled(true);
    }
  }
}
