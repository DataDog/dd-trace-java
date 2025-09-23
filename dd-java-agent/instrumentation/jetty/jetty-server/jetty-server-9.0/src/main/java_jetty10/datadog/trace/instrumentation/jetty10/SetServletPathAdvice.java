package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DD_SERVLET_PATH_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

/**
 * Because we are processing the initial request before the servletPath is set, we must update it
 * when it is actually set.
 */
public class SetServletPathAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void updateServletPath(
      @Advice.This final Request req, @Advice.Argument(0) final String servletPath) {
    if (servletPath != null && !servletPath.isEmpty()) { // bypass cleanup
      Object span = req.getAttribute(DD_SPAN_ATTRIBUTE);
      // Don't want to update while being dispatched to new servlet
      if (span instanceof AgentSpan && req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE) == null) {
        ((AgentSpan) span).setTag(SERVLET_PATH, servletPath);
        req.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);
      }
    }
  }

  private void muzzleCheck(HttpChannel connection, HttpServletRequest request, HttpFields fields) {
    connection.run();
    request.getContextPath();
    fields.getField(0);
  }
}
