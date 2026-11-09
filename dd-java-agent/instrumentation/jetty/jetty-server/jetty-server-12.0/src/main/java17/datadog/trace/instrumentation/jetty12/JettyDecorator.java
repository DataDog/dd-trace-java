package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class JettyDecorator extends HttpServerDecorator<Request, Request, Response, Request> {
  public static final CharSequence JETTY_SERVER = UTF8BytesString.create("jetty-server");
  public static final JettyDecorator DECORATE = new JettyDecorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";
  public static final String DD_PARENT_CONTEXT_ATTRIBUTE = "datadog.parent-context";

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
    // Avoid Request.getRemoteAddr() since ForwardedRequestCustomizer wraps
    // ConnectionMetaData.getRemoteSocketAddress() with the value resolved from
    // x-forwarded-for and similar proxy headers. Peer information must be the actual
    // socket peer.
    final InetSocketAddress remote = remoteSocketAddress(request);
    if (remote != null) {
      final InetAddress address = remote.getAddress();
      if (address != null) {
        return address.getHostAddress();
      }
      return Request.getRemoteAddr(request);
    }
    return Request.getRemoteAddr(request);
  }

  @Override
  protected int peerPort(final Request request) {
    final InetSocketAddress remote = remoteSocketAddress(request);
    if (remote != null) {
      return remote.getPort();
    }
    return Request.getRemotePort(request);
  }

  private static InetSocketAddress remoteSocketAddress(final Request request) {
    final ConnectionMetaData metaData = request.getConnectionMetaData();
    if (metaData == null) {
      return null;
    }
    final Connection connection = metaData.getConnection();
    if (connection == null) {
      return null;
    }
    final EndPoint endPoint = connection.getEndPoint();
    if (endPoint == null) {
      return null;
    }
    final SocketAddress remote = endPoint.getRemoteSocketAddress();
    if (remote instanceof InetSocketAddress) {
      return (InetSocketAddress) remote;
    }
    return null;
  }

  @Override
  protected int status(final Response response) {
    return response.getStatus();
  }

  @Override
  protected String getRequestHeader(final Request request, String key) {
    return request.getHeaders().get(key);
  }

  /**
   * Registers the {@link JettyBlockResponseFunction} on the span request context, right after
   * {@code onRequest}. {@code createBlockResponseFunction} is not used because it only receives the
   * request, and the core request has no public path to its response.
   */
  public void registerBlockResponseFunction(
      final AgentSpan span, final Request request, final Response response) {
    if (!APPSEC_ACTIVE || request == null || response == null) {
      return;
    }
    final RequestContext requestContext = span.getRequestContext();
    if (requestContext != null && requestContext.getBlockResponseFunction() == null) {
      requestContext.setBlockResponseFunction(new JettyBlockResponseFunction(request, response));
    }
  }

  /**
   * Response-phase AppSec analysis runs before the commit, see {@code JettyCommitResponseHelper}.
   */
  @Override
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  /** Decorates the span with the response and finishes it. */
  public void finishSpan(
      final Context context, final AgentSpan span, final Request request, final Response response) {
    onResponse(span, request, response);
    beforeFinish(context);
    span.finish();
  }

  public void onResponse(AgentSpan span, Request request, Response response) {
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
    if (request.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) == null) {
      // the commit hook never saw this response (e.g. a core handler committing it without any
      // write, or AppSec instrumentation not applied): keep response monitoring, too late to block
      int status = response.getStatus();
      callIGCallbackResponseAndHeaders(
          span, response, status == 0 ? 200 : status, ExtractAdapter.Response.GETTER);
    }
    super.onResponse(span, response);
  }
}
