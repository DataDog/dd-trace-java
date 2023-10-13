package datadog.trace.instrumentation.opensearch;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

/**
 * This advice is used to prevent the propagation of a trace across a particular method. This is
 * useful for example a method that would be doing tail call recursion to handle another request.
 */
public class ShadowExistingScopeAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter() {
    return activateSpan(noopSpan());
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.Enter final AgentScope scope) {
    scope.close();
  }
}
