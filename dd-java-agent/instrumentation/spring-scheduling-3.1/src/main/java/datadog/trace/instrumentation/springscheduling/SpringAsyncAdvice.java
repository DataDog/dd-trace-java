package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import net.bytebuddy.asm.Advice;
import org.aopalliance.intercept.MethodInvocation;

public class SpringAsyncAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void scheduleAsync(
      @Advice.Argument(value = 0, readOnly = false) MethodInvocation invocation) {
    // wrap so that when the invocation is invoked, it can be wrapped with a span's start and stop
    invocation = new SpannedMethodInvocation(activeSpan(), invocation);
  }
}
