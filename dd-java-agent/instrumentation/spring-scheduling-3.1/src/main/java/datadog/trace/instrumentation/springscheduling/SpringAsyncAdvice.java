package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import org.aopalliance.intercept.MethodInvocation;

public class SpringAsyncAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void scheduleAsync(
      @Advice.Argument(value = 0, readOnly = false) MethodInvocation invocation) {
    TraceScope scope = activeScope();
    invocation = new SpannedMethodInvocation(null == scope ? null : scope.capture(), invocation);
  }
}
