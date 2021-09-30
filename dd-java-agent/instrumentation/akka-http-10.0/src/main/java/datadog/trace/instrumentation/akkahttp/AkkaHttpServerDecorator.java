package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders.GETTER;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;

public class AkkaHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, HttpRequest, HttpResponse, HttpRequest> {
  public static final AkkaHttpServerDecorator DECORATE = new AkkaHttpServerDecorator();

  private static final CharSequence AKKA_REQUEST = UTF8BytesString.create("akka-http.request");
  private static final CharSequence AKKA_HTTP_SERVER = UTF8BytesString.create("akka-http-server");

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
    return GETTER;
  }

  @Override
  public CharSequence spanName() {
    return AKKA_REQUEST;
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
