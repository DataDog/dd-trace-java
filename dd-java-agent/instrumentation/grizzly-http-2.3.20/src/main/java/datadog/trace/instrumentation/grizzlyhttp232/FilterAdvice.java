package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class FilterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final FilterChainContext ctx) {
    if (activeSpan() != null) {
      return null;
    }
    Object contextObj = ctx.getAttributes().getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextObj instanceof Context) {
      final Context context = (Context) contextObj;
      final AgentSpan span = spanFromContext(context);
      if (span != null) {
        return activateSpan(span);
      }
    }
    return null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
