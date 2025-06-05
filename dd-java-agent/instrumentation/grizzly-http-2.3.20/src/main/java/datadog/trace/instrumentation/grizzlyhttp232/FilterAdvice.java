package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class FilterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final FilterChainContext ctx) {
    Object span = ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    if (span == null || activeSpan() != null) {
      return null;
    }
    return activateSpan((AgentSpan) span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
