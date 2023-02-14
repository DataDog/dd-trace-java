package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.trace_annotation.TraceDecorator.DECORATE;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import net.bytebuddy.asm.Advice;

public class TraceAdvice {
  private static final String DEFAULT_OPERATION_NAME = "trace.annotation";
  private static final Map<String, Set<String>> methodsToMeasure = InstrumenterConfig.get().getMeasuredMethods();
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
<<<<<<< HEAD
<<<<<<< HEAD
    if ((traceAnnotation != null && traceAnnotation.measured())
        || filter(method.getDeclaringClass().getName(), method.getName())) {

      span.setMeasured(true);
=======
    if (traceAnnotation != null && traceAnnotation.measured()) {
      DECORATE.measureSpan(span);
>>>>>>> parent of c591185dec (moved config parsing logic to agent-bootstrap)
=======
    if (traceAnnotation != null && traceAnnotation.measured()) {
      DECORATE.measureSpan(span);
>>>>>>> parent of c591185dec (moved config parsing logic to agent-bootstrap)
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

  public static boolean filter(String clazz, String method){
    return (!methodsToMeasure.isEmpty() && methodsToMeasure!=null)
        && methodsToMeasure.containsKey(clazz) && methodsToMeasure.get(clazz).contains(method);
  }
}
