package datadog.trace.instrumentation.pekkohttp;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class PekkoHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, HttpRequest, HttpResponse, HttpRequest> {
  private static final CharSequence PEKKO_HTTP_SERVER = UTF8BytesString.create("pekko-http-server");

  public static final PekkoHttpServerDecorator DECORATE = new PekkoHttpServerDecorator();
  public static final CharSequence PEKKO_SERVER_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"pekko-http", "pekko-http-server"};
  }

  @Override
  protected CharSequence component() {
    return PEKKO_HTTP_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpRequest> getter() {
    return PekkoHttpServerHeaders.requestGetter();
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return PekkoHttpServerHeaders.responseGetter();
  }

  @Override
  public CharSequence spanName() {
    return PEKKO_SERVER_REQUEST;
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
