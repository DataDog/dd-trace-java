package datadog.trace.instrumentation.liberty20;

import static datadog.trace.instrumentation.liberty20.HttpServletExtractAdapter.Request;
import static datadog.trace.instrumentation.liberty20.HttpServletExtractAdapter.Response;

import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.ws.webcontainer.webapp.WebAppErrorReport;
import com.ibm.wsspi.webcontainer.servlet.IExtendedResponse;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import datadog.trace.instrumentation.servlet.ServletBlockingHelper;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LibertyDecorator
    extends HttpServerDecorator<
        HttpServletRequest, HttpServletRequest, HttpServletResponse, HttpServletRequest> {

  public static final CharSequence LIBERTY_SERVER = UTF8BytesString.create("liberty-server");
  public static final LibertyDecorator DECORATE = new LibertyDecorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  public static final String DD_PARENT_CONTEXT_ATTRIBUTE = "datadog.parent-context";
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"liberty"};
  }

  @Override
  protected CharSequence component() {
    return LIBERTY_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletRequest> getter() {
    return Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletResponse> responseGetter() {
    return Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return SERVLET_REQUEST;
  }

  @Override
  protected String method(final HttpServletRequest request) {
    return request.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final HttpServletRequest request) {
    return request.getRemoteAddr();
  }

  @Override
  protected int peerPort(final HttpServletRequest request) {
    return request.getRemotePort();
  }

  @Override
  protected int status(final HttpServletResponse response) {
    return response.getStatus();
  }

  @Override
  protected String requestedSessionId(final HttpServletRequest request) {
    return request.getRequestedSessionId();
  }

  @Override
  public AgentSpan onResponseStatus(AgentSpan span, int status) {
    Integer currentStatus = (Integer) span.getTag(Tags.HTTP_STATUS);
    // do not set status if the tag is already there and it's an error span
    // we may have the status during response blocking, but in that case
    // the status code is not propagated to the servlet layer
    if (currentStatus == null || !span.isError()) {
      super.onResponseStatus(span, status);
    }
    return span;
  }

  public AgentSpan getPath(AgentSpan span, HttpServletRequest request) {
    if (request != null) {
      String contextPath = request.getContextPath();
      String servletPath = request.getServletPath();

      if (null != contextPath && !contextPath.isEmpty()) {
        span.setTag("servlet.context", contextPath);
      }
      if (null != servletPath && !servletPath.isEmpty()) {
        span.setTag("servlet.path", servletPath);
      }

      request.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
      request.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);
    }
    return span;
  }

  @Override
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  public AgentSpan onResponse(AgentSpan span, SRTServletResponse response) {
    HttpServletRequest req = response.getRequest();

    if (Config.get().isServletPrincipalEnabled() && req.getUserPrincipal() != null) {
      span.setTag(DDTags.USER_NAME, req.getUserPrincipal().getName());
    }
    super.onResponse(span, response);

    Object ex = req.getAttribute("javax.servlet.error.exception");
    Object report;
    Object errorMessage;
    Throwable throwable = null;
    if (ex instanceof Throwable) {
      throwable = (Throwable) ex;
      if (throwable instanceof ServletException) {
        throwable = ((ServletException) throwable).getRootCause();
      }
      onError(span, throwable);
    } else if ((report = req.getAttribute("ErrorReport")) instanceof WebAppErrorReport) {
      // overwrite the HTTP status codes, and if a custom error report is provided by liberty server
      WebAppErrorReport errReport = (WebAppErrorReport) report;
      onError(span, errReport, throwable);
    } else if ((errorMessage = req.getAttribute("javax.servlet.error.message")) instanceof String) {
      span.setError(true);
      span.setTag(DDTags.ERROR_MSG, (String) errorMessage);
    }
    return span;
  }

  public AgentSpan onError(AgentSpan span, WebAppErrorReport report, Throwable servletThrowable) {
    span.setError(true);
    // make sure the two reported throwables are different throwables
    if (report.getCause() != null
        && (servletThrowable == null || servletThrowable.getCause() != report.getCause())) {
      span.addThrowable(report.getCause());
    }
    span.setTag(DDTags.ERROR_MSG, report.getMessage());
    return span;
  }

  public static class LibertyBlockResponseFunction implements BlockResponseFunction {
    private static final Logger log = LoggerFactory.getLogger(LibertyBlockResponseFunction.class);
    private final HttpServletRequest request;

    public LibertyBlockResponseFunction(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType bct,
        Map<String, String> extraHeaders) {
      if (!(request instanceof SRTServletRequest)) {
        log.warn("Can't block; request not of type SRTServletRequest");
        return false;
      }
      IExtendedResponse response = ((SRTServletRequest) request).getResponse();
      if (!(response instanceof HttpServletResponse)) {
        log.warn("Can't block; response not of type HttpServletResponse");
        return false;
      }
      ServletBlockingHelper.commitBlockingResponse(
          segment, request, (HttpServletResponse) response, statusCode, bct, extraHeaders);

      return true;
    }
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      HttpServletRequest httpServletRequest, HttpServletRequest connection) {
    return new LibertyBlockResponseFunction(httpServletRequest);
  }
}
