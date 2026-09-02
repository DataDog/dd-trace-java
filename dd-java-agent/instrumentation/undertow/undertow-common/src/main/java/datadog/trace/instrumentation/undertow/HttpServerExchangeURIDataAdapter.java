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
    return httpServerExchange.getHostName();
  }

  @Override
  public int port() {
    try {
      return httpServerExchange.getHostPort();
    } catch (final NullPointerException e) {
      // Undertow's getHostPort() can NPE internally (e.g. no Host header and a connection whose
      // local address isn't an InetSocketAddress, such as AJP or a Unix domain socket transport).
      return 0;
    }
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
