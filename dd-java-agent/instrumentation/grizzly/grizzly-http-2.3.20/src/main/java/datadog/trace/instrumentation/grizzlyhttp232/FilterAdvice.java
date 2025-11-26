package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;

import datadog.context.Context;
import datadog.context.ContextScope;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class FilterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(@Advice.Argument(0) final FilterChainContext ctx) {
    if (getCurrentContext() != getRootContext()) {
      return null;
    }
    Object contextObj = ctx.getAttributes().getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextObj instanceof Context) {
      return ((Context) contextObj).attach();
    }
    return null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Enter final ContextScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
