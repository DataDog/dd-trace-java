package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;
import static datadog.trace.bootstrap.debugger.DebuggerContext.marker;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.trace_annotation.TraceDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class TraceAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Origin final Method method) {
    AgentScope agentScope = activateSpan(DECORATE.startMethodSpan(method));
    marker();
    captureCodeOrigin(method, true);
    return agentScope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result,
      @Advice.Origin final MethodType methodType,
      @Advice.Thrown final Throwable throwable) {
    DECORATE.onError(scope, throwable);
    DECORATE.beforeFinish(scope);
    scope.close();
    // we must check against the method return signature and not the type of the returned object
    // otherwise we'll risk to have class cast at runtime
    result = DECORATE.wrapAsyncResultOrFinishSpan(result, methodType.returnType(), scope.span());
  }
}
