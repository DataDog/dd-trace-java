package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.trace_annotation.TraceDecorator.DECORATE;

import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class TraceAdvice {
  private static final String DEFAULT_OPERATION_NAME = "trace.annotation";
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Origin final Method method,@Advice.AllArguments final Object[] args) {
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

    Parameter[] parameters = method.getParameters();
    StringBuffer methodName = new StringBuffer(method.getName());
    methodName.append("(");
    Integer max_size = 1024;
    if (parameters.length>0){
      for (int i = 0; i < parameters.length; i++) {
        if (args[i]==null){
          span.setTag(parameters[i].getName(),"null");
        }else {
          String value = args[i].toString();
          span.setTag(parameters[i].getName(), args[i].toString().substring(0,value.length()>=max_size?max_size:value.length()));
        }
        methodName.append(parameters[i].getType().getTypeName()).append(" ").append(parameters[i].getName());
        if (i<parameters.length-1){
          methodName.append(",");
        }
      }
    }
    methodName.append(")");
    span.setTag("method_name",methodName.toString());
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
      @Advice.Enter final AgentScope scope,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result,
      @Advice.Thrown final Throwable throwable) {
    DECORATE.onError(scope, throwable);
    DECORATE.beforeFinish(scope);
    scope.close();
    result = DECORATE.wrapAsyncResultOrFinishSpan(result, scope.span());
  }
}
