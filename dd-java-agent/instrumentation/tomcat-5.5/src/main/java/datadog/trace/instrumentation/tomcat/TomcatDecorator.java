package datadog.trace.instrumentation.tomcat;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

public class TomcatDecorator extends HttpServerDecorator<Request, Request, Response> {
  public static final CharSequence SERVLET_REQUEST = UTF8BytesString.create("servlet.request");
  public static final CharSequence TOMCAT_SERVER = UTF8BytesString.create("tomcat-server");
  public static final TomcatDecorator DECORATE = new TomcatDecorator();
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"tomcat"};
  }

  @Override
  protected CharSequence component() {
    return TOMCAT_SERVER;
  }

  @Override
  protected String method(final Request request) {
    return request.getMethod();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.getRemoteAddr();
  }

  @Override
  protected int peerPort(final Request request) {
    return request.getRemotePort();
  }

  @Override
  protected int status(final Response response) {
    return response.getStatus();
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final Request request) {
    assert span != null;
    if (request != null) {
      String contextPath = request.getContextPath();
      String servletPath = request.getServletPath();

      span.setTag("servlet.context", contextPath);
      span.setTag("servlet.path", servletPath);

      // Used by AsyncContextInstrumentation because the context path may be reset
      // by the time the async context is dispatched.
      request.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
      request.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);
    }
    return super.onRequest(span, request);
  }

  @Override
  public AgentSpan onResponse(AgentSpan span, Response response) {
    Request req = response.getRequest();
    if (Config.get().isServletPrincipalEnabled() && req.getUserPrincipal() != null) {
      span.setTag(DDTags.USER_NAME, req.getUserPrincipal().getName());
    }
    Object ex = req.getAttribute("javax.servlet.error.exception");
    if (ex instanceof Throwable) {
      Throwable throwable = (Throwable) ex;
      if (throwable instanceof ServletException) {
        throwable = ((ServletException) throwable).getRootCause();
      }
      onError(span, throwable);
    }
    return super.onResponse(span, response);
  }
}
