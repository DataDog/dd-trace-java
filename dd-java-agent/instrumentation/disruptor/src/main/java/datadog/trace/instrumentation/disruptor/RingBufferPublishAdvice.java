package datadog.trace.instrumentation.disruptor;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.disruptor.DisruptorDecorator.DECORATE;

public class RingBufferPublishAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(@Advice.Argument(0) Long sequence) {

    AgentScope scope = DECORATE.start();
    if (scope == null) {
      // scope = activeSpan();
      AgentSpan agentSpan = activeSpan();
      scope = activateSpan(agentSpan);
    }
    InstrumentationContext.get(Long.class, AgentSpan.class).put(sequence, scope.span());
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATE.onError(scope.span(), throwable);
    DECORATE.beforeFinish(scope.span());

    scope.close();
    scope.span().finish();
  }
}
