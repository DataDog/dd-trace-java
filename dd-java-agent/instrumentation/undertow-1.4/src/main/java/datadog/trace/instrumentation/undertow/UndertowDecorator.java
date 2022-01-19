package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.undertow.server.HttpServerExchange;

import static datadog.trace.instrumentation.undertow.UndertowExtractAdapter.GETTER;

public class UndertowDecorator
    extends HttpServerDecorator<
        HttpServerExchange, HttpServerExchange, HttpServerExchange, HttpServerExchange> {
  public static final CharSequence UNDERTOW_REQUEST =
      UTF8BytesString.create("undertow-http.request");
  public static final CharSequence UNDERTOW_HTTP_SERVER =
      UTF8BytesString.create("undertow-http-server");
  public static final UndertowDecorator DECORATE = new UndertowDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"undertow-http", "undertow-http-server"};
  }

  @Override
  protected CharSequence component() {
    return UNDERTOW_HTTP_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServerExchange> getter() {
    return GETTER;
  }

  @Override
  public CharSequence spanName() {
    return UNDERTOW_REQUEST;
  }

  @Override
  protected String method(final HttpServerExchange exchange) {
    return exchange.getRequestMethod().toString();
  }

  @Override
  protected URIDataAdapter url(final HttpServerExchange exchange) {
    return new HttpServerExchangeURIDataAdapter(exchange);
  }

  @Override
  protected String peerHostIP(final HttpServerExchange exchange) {
    return exchange.getDestinationAddress().getAddress().getHostAddress();
  }

  @Override
  protected int peerPort(final HttpServerExchange exchange) {
    return exchange.getDestinationAddress().getPort();
  }

  @Override
  protected int status(final HttpServerExchange exchange) {
    return exchange.getResponseCode();
  }
}
