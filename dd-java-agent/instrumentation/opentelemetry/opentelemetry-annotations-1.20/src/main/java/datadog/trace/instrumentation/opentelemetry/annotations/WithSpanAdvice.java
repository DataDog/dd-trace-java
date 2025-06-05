package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.opentelemetry.annotations.WithSpanDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class WithSpanAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Origin final Method method) {
    AgentSpan span = DECORATE.startMethodSpan(method);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,
      @Advice.Origin final MethodType methodType,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result,
      @Advice.Thrown final Throwable throwable) {
    DECORATE.onError(scope, throwable);
    DECORATE.beforeFinish(scope);
    scope.close();
    result = DECORATE.wrapAsyncResultOrFinishSpan(result, methodType.returnType(), scope.span());
  }
}
