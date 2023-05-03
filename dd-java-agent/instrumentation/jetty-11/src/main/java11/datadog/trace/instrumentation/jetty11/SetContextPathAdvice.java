package datadog.trace.instrumentation.jetty11;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty11.JettyDecorator.DD_CONTEXT_PATH_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty11.JettyDecorator.DD_SERVLET_PATH_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Because we are processing the initial request before the contextPath is set, we must update it
 * when it is actually set.
 */
public class SetContextPathAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void updateContextPath(
      @Advice.This final Request req,
      @Advice.Argument(0) final ContextHandler.Context context,
      @Advice.Argument(1) final String pathInContext) {
    Object span = req.getAttribute(DD_SPAN_ATTRIBUTE);
    // Don't want to update while being dispatched to new servlet
    if (span instanceof AgentSpan && req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE) == null) {
      if (context != null && context.getContextPath() != null) {
        final String servletContext = context.getContextPath();
        ((AgentSpan) span).setTag(SERVLET_CONTEXT, servletContext);
        req.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, servletContext);
        if (pathInContext != null) {
          final String relativePath =
              pathInContext.startsWith(servletContext)
                  ? pathInContext.substring(servletContext.length())
                  : pathInContext;
          ((AgentSpan) span).setTag(SERVLET_PATH, relativePath);
          req.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, relativePath);
        }
      }
    }
  }
}
