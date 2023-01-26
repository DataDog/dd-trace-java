package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.trace_annotation.TraceDecorator.DECORATE;

import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class TraceAdvice {
  private static final String DEFAULT_OPERATION_NAME = "trace.annotation";

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Origin final Method method) {
    final Trace traceAnnotation = method.getAnnotation(Trace.class);
    CharSequence operationName = traceAnnotation == null ? null : traceAnnotation.operationName();

    if (operationName == null || operationName.length() == 0) {
      if (DECORATE.useLegacyOperationName()) {
        operationName = DEFAULT_OPERATION_NAME;
      } else {
        operationName = DECORATE.spanNameForMethod(method);
      }
    }

    final AgentSpan span = startSpan(operationName);

    CharSequence resourceName = traceAnnotation == null ? null : traceAnnotation.resourceName();
    if (resourceName == null || resourceName.length() == 0) {
      resourceName = DECORATE.spanNameForMethod(method);
    }
    span.setResourceName(resourceName);
    DECORATE.afterStart(span);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    return scope;
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
