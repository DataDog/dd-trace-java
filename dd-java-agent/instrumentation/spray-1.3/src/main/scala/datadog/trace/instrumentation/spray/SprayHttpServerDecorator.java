package datadog.trace.instrumentation.spray;

import static datadog.trace.instrumentation.spray.SprayHeaders.Request;
import static datadog.trace.instrumentation.spray.SprayHeaders.Response;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import spray.http.HttpRequest;
import spray.http.HttpResponse;
import spray.routing.RequestContext;

public class SprayHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, RequestContext, HttpResponse, HttpRequest> {
  public static final CharSequence SPRAY_HTTP_SERVER = UTF8BytesString.create("spray-http-server");

  public static final SprayHttpServerDecorator DECORATE = new SprayHttpServerDecorator();

  private static final CharSequence SPRAY_HTTP_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected AgentPropagation.ContextVisitor<HttpRequest> getter() {
    return Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return SPRAY_HTTP_REQUEST;
  }

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
