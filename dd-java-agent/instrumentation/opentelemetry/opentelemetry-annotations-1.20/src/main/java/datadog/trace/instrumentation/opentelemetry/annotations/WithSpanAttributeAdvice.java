package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.opentelemetry.annotations.WithSpanDecorator.DECORATE;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class WithSpanAttributeAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(
      @Advice.Origin final Method method, @Advice.AllArguments final Object[] args) {
    AgentSpan span = DECORATE.startMethodSpan(method);
    DECORATE.addTagsFromMethodArgs(span, method, args);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
    DECORATE.onError(scope, throwable);
    DECORATE.beforeFinish(scope);
    scope.close();
    spanFromScope(scope).finish();
  }
}
