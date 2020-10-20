package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class FilterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This BaseFilter it, @Advice.Argument(0) final FilterChainContext ctx) {
    if (ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE) == null || activeScope() != null) {
      return null;
    }
    AgentScope scope =
        activateSpan((AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE));
    scope.setAsyncPropagation(true);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.This BaseFilter it, @Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.setAsyncPropagation(false);
      scope.close();
    }
  }
}
