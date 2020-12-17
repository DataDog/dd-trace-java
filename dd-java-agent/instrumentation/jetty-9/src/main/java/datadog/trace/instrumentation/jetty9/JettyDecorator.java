package datadog.trace.instrumentation.jetty9;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class JettyDecorator
    extends HttpServerDecorator<HttpServletRequest, HttpServletRequest, HttpServletResponse> {
  public static final CharSequence SERVLET_REQUEST = UTF8BytesString.create("servlet.request");
  public static final CharSequence JETTY_SERVER = UTF8BytesString.create("jetty-server");
  public static final JettyDecorator DECORATE = new JettyDecorator();
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jetty", "jetty-9"};
  }

  @Override
  protected CharSequence component() {
    return JETTY_SERVER;
  }

  @Override
  protected String method(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest httpServletRequest) {
    return new RequestURIDataAdapter(httpServletRequest);
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
}
