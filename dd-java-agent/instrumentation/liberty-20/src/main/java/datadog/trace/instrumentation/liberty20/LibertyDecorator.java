package datadog.trace.instrumentation.liberty20;

import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.ws.webcontainer.webapp.WebAppErrorReport;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LibertyDecorator
    extends HttpServerDecorator<HttpServletRequest, HttpServletRequest, HttpServletResponse> {

  public static final CharSequence SERVLET_REQUEST = UTF8BytesString.create("servlet.request");
  public static final CharSequence LIBERTY_SERVER = UTF8BytesString.create("liberty-server");
  public static final LibertyDecorator DECORATE = new LibertyDecorator();
  public static final String DD_EXTRACTED_CONTEXT_ATTRIBUTE = "datadog.extracted-context";
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

  public AgentSpan onResponse(AgentSpan span, SRTServletResponse response) {
    HttpServletRequest req = response.getRequest();

    if (Config.get().isServletPrincipalEnabled() && req.getUserPrincipal() != null) {
      span.setTag(DDTags.USER_NAME, req.getUserPrincipal().getName());
    }
    super.onResponse(span, response);

    Object ex = req.getAttribute("javax.servlet.error.exception");
    Object report = req.getAttribute("ErrorReport");
    Throwable throwable = null;
    if (ex instanceof Throwable) {
      throwable = (Throwable) ex;
      if (throwable instanceof ServletException) {
        throwable = ((ServletException) throwable).getRootCause();
      }
      onError(span, throwable);
    }

    // overwrite the HTTP status codes, and if a custom error report is provided by liberty server
    if (report instanceof WebAppErrorReport) {
      WebAppErrorReport errReport = (WebAppErrorReport) report;
      onError(span, errReport, throwable);
    }
    return span;
  }

  public AgentSpan onError(AgentSpan span, WebAppErrorReport report, Throwable servletThrowable) {
    span.setError(true);
    span.setTag(Tags.HTTP_STATUS, report.getErrorCode());
    // make sure the two reported throwables are different throwables
    if (report.getCause() != null
        && (servletThrowable == null || servletThrowable.getCause() != report.getCause())) {
      span.addThrowable(report.getCause());
    }
    span.setTag(DDTags.ERROR_MSG, report.getMessage());
    return span;
  }
}
