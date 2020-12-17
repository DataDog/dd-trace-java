package datadog.trace.instrumentation.jetty76;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class JettyDecorator extends HttpServerDecorator<Request, Request, Response> {
  public static final CharSequence SERVLET_REQUEST = UTF8BytesString.create("servlet.request");
  public static final CharSequence JETTY_SERVER = UTF8BytesString.create("jetty-server");
  public static final JettyDecorator DECORATE = new JettyDecorator();
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
  protected String method(final Request Request) {
    return Request.getMethod();
  }

  @Override
  protected URIDataAdapter url(final Request Request) {
    return new RequestURIDataAdapter(Request);
  }

  @Override
  protected String peerHostIP(final Request Request) {
    return Request.getRemoteAddr();
  }

  @Override
  protected int peerPort(final Request Request) {
    return Request.getRemotePort();
  }

  @Override
  protected int status(final Response response) {
    return response.getStatus();
  }
}
