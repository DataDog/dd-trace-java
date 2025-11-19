package datadog.trace.instrumentation.jetty76;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import datadog.trace.instrumentation.jetty.JettyBlockResponseFunction;
import javax.servlet.ServletException;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class JettyDecorator extends HttpServerDecorator<Request, Request, Response, Request> {
  public static final CharSequence JETTY_SERVER = UTF8BytesString.create("jetty-server");
  public static final JettyDecorator DECORATE = new JettyDecorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jetty"};
  }

  @Override
  protected CharSequence component() {
    return JETTY_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Request> getter() {
    return ExtractAdapter.Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Response> responseGetter() {
    return ExtractAdapter.Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return SERVLET_REQUEST;
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
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  @Override
  protected String getRequestHeader(final Request request, String key) {
    return request.getHeader(key);
  }

  public AgentSpan onResponse(AgentSpan span, AbstractHttpConnection connection) {
    Request request = connection.getRequest();
    Response response = connection.getResponse();
    if (Config.get().isServletPrincipalEnabled() && request.getUserPrincipal() != null) {
      span.setTag(DDTags.USER_NAME, request.getUserPrincipal().getName());
    }
    Object ex = request.getAttribute("javax.servlet.error.exception");
    if (ex instanceof Throwable) {
      Throwable throwable = (Throwable) ex;
      if (throwable instanceof ServletException) {
        throwable = ((ServletException) throwable).getRootCause();
      }
      onError(span, throwable);
    }
    return super.onResponse(span, response);
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(Request request, Request connection) {
    return new JettyBlockResponseFunction(request);
  }
}
