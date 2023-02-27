package datadog.trace.instrumentation.akkahttp;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;

public class AkkaHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, HttpRequest, HttpResponse, HttpRequest> {
  private static final CharSequence AKKA_HTTP_SERVER = UTF8BytesString.create("akka-http-server");

  public static final AkkaHttpServerDecorator DECORATE = new AkkaHttpServerDecorator();
  public static final CharSequence AKKA_SERVER_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"akka-http", "akka-http-server"};
  }

  @Override
  protected CharSequence component() {
    return AKKA_HTTP_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpRequest> getter() {
    return AkkaHttpServerHeaders.requestGetter();
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return AkkaHttpServerHeaders.responseGetter();
  }

  @Override
  public CharSequence spanName() {
    return AKKA_SERVER_REQUEST;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.method().value();
  }

  @Override
  protected URIDataAdapter url(final HttpRequest httpRequest) {
    return new UriAdapter(httpRequest.uri());
  }

  @Override
  protected String peerHostIP(final HttpRequest httpRequest) {
    return null;
  }

  @Override
  protected int peerPort(final HttpRequest httpRequest) {
    return 0;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.status().intValue();
  }
}
