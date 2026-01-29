package datadog.trace.instrumentation.tomcat;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.util.Map;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

public class TomcatDecorator
    extends HttpServerDecorator<Request, Request, Response, org.apache.coyote.Request> {
  public static final CharSequence TOMCAT_SERVER = UTF8BytesString.create("tomcat-server");

  public static final TomcatDecorator DECORATE = new TomcatDecorator();
  public static final String DD_PARENT_CONTEXT_ATTRIBUTE = "datadog.parent-context";
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";
  public static final String DD_REAL_STATUS_CODE = "datadog.servlet.real_status_code";
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"tomcat"};
  }

  @Override
  protected CharSequence component() {
    return TOMCAT_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<org.apache.coyote.Request> getter() {
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
  protected String getRequestHeader(final Request request, String key) {
    return request.getHeader(key);
  }

  @Override
  protected int status(final Response response) {
    int status = response.getStatus();
    if (status == 500) {
      Integer savedStatus = (Integer) response.getRequest().getAttribute(DD_REAL_STATUS_CODE);
      if (savedStatus != null) {
        return savedStatus;
      }
    }
    return status;
  }

  @Override
  protected String requestedSessionId(final Request request) {
    return request.getRequestedSessionId();
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final Request connection,
      final Request request,
      final Context parentContext) {
    if (request != null) {
      String contextPath = request.getContextPath();
      String servletPath = request.getServletPath();

      if (null == contextPath || contextPath.isEmpty()) {
        contextPath = "/";
      }
      span.setTag("servlet.context", contextPath);
      if (null != servletPath && !servletPath.isEmpty()) {
        span.setTag("servlet.path", servletPath);
      }

      // Used by AsyncContextInstrumentation because the context path may be reset
      // by the time the async context is dispatched.
      request.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
      request.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);
    }
    return super.onRequest(span, connection, request, parentContext);
  }

  @Override
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  @Override
  public AgentSpan onResponse(AgentSpan span, Response response) {
    Request req = response.getRequest();
    if (Config.get().isServletPrincipalEnabled() && req.getUserPrincipal() != null) {
      span.setTag(DDTags.USER_NAME, req.getUserPrincipal().getName());
    }
    Object throwable = req.getAttribute("javax.servlet.error.exception");
    if (throwable == null) {
      // Servlet 4+ (Tomcat 10+)
      throwable = req.getAttribute("jakarta.servlet.error.exception");
    }
    if (throwable instanceof Throwable) {
      onError(span, (Throwable) throwable);
    }
    return super.onResponse(span, response);
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      final Request request, Request connection) {
    return new TomcatBlockResponseFunction(request);
  }

  public static class TomcatBlockResponseFunction implements BlockResponseFunction {
    private final Request request;

    public TomcatBlockResponseFunction(Request request) {
      this.request = request;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType bct,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      return TomcatBlockingHelper.commitBlockingResponse(
          segment,
          request,
          request.getResponse(),
          statusCode,
          bct,
          extraHeaders,
          securityResponseId);
    }
  }
}
