package datadog.trace.instrumentation.jsp;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.net.URI;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import org.apache.jasper.JspCompilationContext;

public class JSPDecorator extends BaseDecorator {
  public static final CharSequence JSP_COMPILE = UTF8BytesString.create("jsp.compile");
  public static final CharSequence JSP_RENDER = UTF8BytesString.create("jsp.render");
  public static final CharSequence JSP_HTTP_SERVLET = UTF8BytesString.create("jsp-http-servlet");
  public static JSPDecorator DECORATE = new JSPDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jsp"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JSP_HTTP_SERVLET;
  }

  public void onCompile(final AgentScope scope, final JspCompilationContext jspCompilationContext) {
    if (jspCompilationContext != null) {
      final AgentSpan span = scope.span();
      span.setResourceName(jspCompilationContext.getJspFile());

      if (jspCompilationContext.getServletContext() != null) {
        span.setTag("servlet.context", jspCompilationContext.getServletContext().getContextPath());
      }

      if (jspCompilationContext.getCompiler() != null) {
        span.setTag("jsp.compiler", jspCompilationContext.getCompiler().getClass().getName());
      }
      span.setTag("jsp.classFQCN", jspCompilationContext.getFQCN());
    }
  }

  public void onRender(final AgentSpan span, final HttpServletRequest req) {
    // get the JSP file name being rendered in an include action
    final Object includeServletPath = req.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
    String resourceName = req.getServletPath();
    if (includeServletPath instanceof String) {
      resourceName = includeServletPath.toString();
    }
    span.setResourceName(resourceName);

    final Object forwardOrigin = req.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH);
    if (forwardOrigin instanceof String) {
      span.setTag("jsp.forwardOrigin", forwardOrigin.toString());
    }

    // add the request URL as a tag to provide better context when looking at spans produced by
    // actions. Tomcat 9 has relative path symbols in the value returned from
    // HttpServletRequest#getRequestURL(),
    // normalizing the URL should remove those symbols for readability and consistency
    try {
      // note: getRequestURL is supposed to always be nonnull - however servlet wrapping can happen
      // and we never know if ever this can happen
      final StringBuffer requestURL = req.getRequestURL();
      if (requestURL != null && requestURL.length() > 0) {
        span.setTag("jsp.requestURL", (new URI(requestURL.toString())).normalize().toString());
      }
    } catch (final Throwable ignored) {
      // logging here will be too verbose
    }
  }
}
