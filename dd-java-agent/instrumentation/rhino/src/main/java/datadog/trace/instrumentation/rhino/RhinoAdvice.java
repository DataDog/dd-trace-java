package datadog.trace.instrumentation.rhino;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.rhino.RhinoDecorator.DECORATOR;

public class RhinoAdvice {


  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope execute( @Advice.AllArguments final Object[] args) {
    String script = (String)args[1];
    String scriptName = (String)args[2];

    AgentSpan span = DECORATOR.createSpan(scriptName,script);
    AgentScope agentScope = activateSpan(span);
    return agentScope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATOR.onError(scope.span(),throwable);
    DECORATOR.beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }
}
