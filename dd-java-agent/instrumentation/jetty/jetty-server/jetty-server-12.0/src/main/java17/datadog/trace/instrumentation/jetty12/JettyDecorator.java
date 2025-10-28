package datadog.trace.instrumentation.jetty12;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.internal.HttpChannelState;

public class JettyDecorator extends HttpServerDecorator<Request, Request, Response, Request> {
  public static final CharSequence JETTY_SERVER = UTF8BytesString.create("jetty-server");
  public static final JettyDecorator DECORATE = new JettyDecorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  private static final Class<?> JAVAX_SERVLET_EXCEPTION_CLS =
      findClassIfExists("javax.servlet.ServletException");
  private static final Class<?> JAKARTA_SERVLET_EXCEPTION_CLS =
      findClassIfExists("jakarta.servlet.ServletException");

  private static Class<?> findClassIfExists(final String name) {
    try {
      return Class.forName(name, false, JettyDecorator.class.getClassLoader());
    } catch (ClassNotFoundException e) {
    }
    return null;
  }

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
    return Request.getRemoteAddr(request);
  }

  @Override
  protected int peerPort(final Request request) {
    return Request.getRemotePort(request);
  }

  @Override
  protected int status(final Response response) {
    return response.getStatus();
  }

  public AgentSpan onResponse(AgentSpan span, HttpChannelState channel) {
    Request request = channel.getRequest();
    Response response = channel.getResponse();
    if (Config.get().isServletPrincipalEnabled()) {
      final Request.AuthenticationState authenticationState =
          Request.getAuthenticationState(request);
      if (authenticationState != null && authenticationState.getUserPrincipal() != null) {
        span.setTag(DDTags.USER_NAME, authenticationState.getUserPrincipal().getName());
      }
    }
    Object ex = request.getAttribute("jakarta.servlet.error.exception");
    if (ex == null) {
      ex = request.getAttribute("javax.servlet.error.exception");
    }
    if (ex instanceof Throwable) {
      Throwable throwable = (Throwable) ex;
      if ((JAVAX_SERVLET_EXCEPTION_CLS != null && JAVAX_SERVLET_EXCEPTION_CLS.isInstance(throwable))
          || (JAKARTA_SERVLET_EXCEPTION_CLS != null
              && JAKARTA_SERVLET_EXCEPTION_CLS.isInstance(throwable))) {
        // unwrap using getCause that's equivalent to servletException.getRootCause
        throwable = throwable.getCause();
      }
      onError(span, throwable);
    }
    return super.onResponse(span, response);
  }
}
