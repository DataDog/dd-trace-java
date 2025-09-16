package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DD_CONTEXT_PATH_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;

/**
 * Because we are processing the initial request before the contextPath is set, we must update it
 * when it is actually set.
 */
public class SetContextPathAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void updateContextPath(
      @Advice.This final Request req, @Advice.Argument(0) final String contextPath) {
    if (contextPath != null) {
      Object span = req.getAttribute(DD_SPAN_ATTRIBUTE);
      // Don't want to update while being dispatched to new servlet
      if (span instanceof AgentSpan && req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE) == null) {
        ((AgentSpan) span).setTag(SERVLET_CONTEXT, contextPath);
        req.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
      }
    }
  }
}
