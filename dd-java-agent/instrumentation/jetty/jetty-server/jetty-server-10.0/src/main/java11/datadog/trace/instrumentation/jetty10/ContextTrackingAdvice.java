package datadog.trace.instrumentation.jetty10;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_PARENT_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

@AppliesOn(CONTEXT_TRACKING)
public class ContextTrackingAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope enter(@Advice.This final HttpChannel httpChannel) {
    final Request request = httpChannel.getRequest();
    final Object contextObj = request.getAttribute(DD_PARENT_CONTEXT_ATTRIBUTE);
    if (contextObj instanceof Context) {
      return ((Context) contextObj).attach();
    }
    final Context parent = DECORATE.extract(request);
    request.setAttribute(DD_PARENT_CONTEXT_ATTRIBUTE, parent);
    return parent.attach();
  }

  public static void exit(@Advice.Enter final ContextScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
