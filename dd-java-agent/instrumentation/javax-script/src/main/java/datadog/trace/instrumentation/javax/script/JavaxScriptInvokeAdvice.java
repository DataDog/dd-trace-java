package datadog.trace.instrumentation.javax.script;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.javax.script.JavaxScriptDecorator.DECORATE;

public class JavaxScriptInvokeAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(@Advice.Argument(0) String name) {
    final AgentSpan span = startSpan(JavaxScriptDecorator.OPERATION_NAME+"/invoke");
    span.setTag("invoke_function",name);
    final AgentScope scope = activateSpan(span);

    DECORATE.afterStart(span);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }

    AgentSpan span = scope.span();
    if (null != throwable) {
      DECORATE.onError(span, throwable);
    }
    DECORATE.beforeFinish(span);
    scope.close();
    span.finish();
  }
}
