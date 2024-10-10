package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static datadog.trace.instrumentation.trace_annotation.TraceDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.lang.invoke.MethodType;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class DoNotTraceAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope before() {
    return activateSpan(blackholeSpan());
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void after(
      @Advice.Enter final AgentScope scope,
      @Advice.Origin final MethodType methodType,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result) {
    if (scope != null) {
      scope.close();
      result = DECORATE.wrapAsyncResultOrFinishSpan(result, methodType.returnType(), scope.span());
    }
  }
}
