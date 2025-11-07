package datadog.trace.instrumentation.servlet3;

import datadog.context.Context;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class Servlet3Decorator
    extends HttpServerDecorator<
        HttpServletRequest, HttpServletRequest, HttpServletResponse, HttpServletRequest> {
  public static final CharSequence JAVA_WEB_SERVLET = UTF8BytesString.create("java-web-servlet");

  public static final Servlet3Decorator DECORATE = new Servlet3Decorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-3"};
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletRequest> getter() {
    return HttpServletExtractAdapter.Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletResponse> responseGetter() {
    return HttpServletExtractAdapter.Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return SERVLET_REQUEST;
  }

  @Override
  protected String method(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest httpServletRequest) {
    return new ServletRequestURIAdapter(httpServletRequest);
  }

  @Override
  protected String peerHostIP(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemoteAddr();
  }

  @Override
  protected int peerPort(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemotePort();
  }

  @Override
  protected int status(final HttpServletResponse httpServletResponse) {
    return httpServletResponse.getStatus();
  }

  @Override
  protected String requestedSessionId(final HttpServletRequest request) {
    return request.getRequestedSessionId();
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final HttpServletRequest connection,
      final HttpServletRequest request,
      final Context parentContext) {
    assert span != null;
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span);
    if (request != null) {
      String contextPath = request.getContextPath();
      String servletPath = request.getServletPath();

      span.setTag("servlet.context", contextPath);
      span.setTag("servlet.path", servletPath);

      // Used by AsyncContextInstrumentation because the context path may be reset
      // (eg by jetty) by the time the async context is dispatched.
      request.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
      request.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);
    }
    return super.onRequest(span, connection, request, parentContext);
  }

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable instanceof ServletException && throwable.getCause() != null) {
      super.onError(span, throwable.getCause());
    } else {
      super.onError(span, throwable);
    }
    return span;
  }
}
