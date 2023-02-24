package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.trace_annotation.TraceDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class TraceAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Origin final Method method) {
    return activateSpan(DECORATE.startMethodSpan(method));
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    DECORATE.onError(scope, throwable);
    DECORATE.beforeFinish(scope);
    scope.close();
    scope.span().finish();
  }
}
