package datadog.trace.instrumentation.mulehttpconnector.filterchain;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.SPAN;

public class FilterchainAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final FilterChainContext ctx) {

    if (ctx.getAttributes().getAttribute(SPAN) == null || activeScope() != null) {
      return null;
    }

    final AgentScope scope =
        activateSpan((AgentSpan) ctx.getAttributes().getAttribute(SPAN), false);
    scope.setAsyncPropagation(true);

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Thrown final Throwable throwable) {

    if (scope != null) {
      scope.setAsyncPropagation(false);
      scope.close();
    }
  }
}
