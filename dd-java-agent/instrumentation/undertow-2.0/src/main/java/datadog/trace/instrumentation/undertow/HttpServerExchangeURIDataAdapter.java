package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import io.undertow.server.HttpServerExchange;

final class HttpServerExchangeURIDataAdapter extends URIRawDataAdapter {
  private final HttpServerExchange httpServerExchange;

  public HttpServerExchangeURIDataAdapter(final HttpServerExchange httpServerExchange) {
    this.httpServerExchange = httpServerExchange;
  }

  @Override
  public String scheme() {
    return httpServerExchange.getRequestScheme();
  }

  @Override
  public String host() {
    return httpServerExchange.getDestinationAddress().getHostName();
  }

  @Override
  public int port() {
    return httpServerExchange.getDestinationAddress().getPort();
  }

  @Override
  protected String innerRawPath() {
    return httpServerExchange.getRequestURI();
  }

  @Override
  protected String innerRawQuery() {
    return httpServerExchange.getQueryString();
  }

  @Override
  public String fragment() {
    return null;
  }
}
