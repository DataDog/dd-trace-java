package datadog.trace.instrumentation.akkahttp;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.headers.RemoteAddress;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import datadog.trace.instrumentation.akkahttp.appsec.AkkaBlockResponseFunction;
import java.net.InetAddress;
import java.util.Optional;
import scala.Option;
import scala.reflect.ClassTag$;

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
    Option<HttpHeader> header = httpRequest.header(ClassTag$.MODULE$.apply(RemoteAddress.class));
    if (!header.isEmpty()) {
      RemoteAddress httpHeader = (RemoteAddress) header.get();
      akka.http.javadsl.model.RemoteAddress remAddress = httpHeader.address();
      Optional<InetAddress> address = remAddress.getAddress();
      if (address.isPresent()) {
        return address.get().getHostAddress();
      }
    }
    return null;
  }

  @Override
  protected int peerPort(final HttpRequest httpRequest) {
    Option<HttpHeader> header = httpRequest.header(ClassTag$.MODULE$.apply(RemoteAddress.class));
    if (!header.isEmpty()) {
      RemoteAddress httpHeader = (RemoteAddress) header.get();
      akka.http.javadsl.model.RemoteAddress address = httpHeader.address();
      return address.getPort();
    }
    return 0;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.status().intValue();
  }

  @Override
  protected String getRequestHeader(HttpRequest httpRequest, String key) {
    Optional<akka.http.javadsl.model.HttpHeader> header = httpRequest.getHeader(key);
    return header.map(HttpHeader::value).orElse(null);
  }

  @Override
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      HttpRequest httpRequest, HttpRequest httpRequest2) {
    return new AkkaBlockResponseFunction(httpRequest);
  }
}
