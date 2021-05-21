package datadog.trace.instrumentation.spray;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import spray.http.HttpRequest;
import spray.http.HttpResponse;
import spray.routing.RequestContext;

public class SprayHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, RequestContext, HttpResponse> {
  public static final CharSequence SPRAY_HTTP_REQUEST =
      UTF8BytesString.create("spray-http.request");
  public static final CharSequence SPRAY_HTTP_SERVER = UTF8BytesString.create("spray-http-server");

  public static final SprayHttpServerDecorator DECORATE = new SprayHttpServerDecorator();

  @Override
  protected String method(HttpRequest request) {
    return request.method().name();
  }

  @Override
  protected URIDataAdapter url(HttpRequest request) {
    return new SprayURIAdapter(request.uri());
  }

  @Override
  protected String peerHostIP(RequestContext ctx) {
    return null;
  }

  @Override
  protected int peerPort(RequestContext requestContext) {
    // TODO : add support of client/peer port
    return 0;
  }

  @Override
  protected int status(HttpResponse httpResponse) {
    return httpResponse.status().intValue();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spray"};
  }

  @Override
  protected CharSequence component() {
    return SPRAY_HTTP_SERVER;
  }
}
