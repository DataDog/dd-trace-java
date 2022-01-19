package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import io.undertow.server.HttpServerExchange;

final class HttpServerExchangeURIDataAdapter extends URIDataAdapterBase {
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
    return httpServerExchange.getSourceAddress().getHostString();
  }

  @Override
  public int port() {
    return httpServerExchange.getSourceAddress().getPort();
  }

  @Override
  public String path() {
    return httpServerExchange.getRequestPath();
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  public String query() {
    return httpServerExchange.getQueryString();
  }

  @Override
  public boolean supportsRaw() {
    return false;
  }

  @Override
  public String rawPath() {
    return null;
  }

  @Override
  public String rawQuery() {
    return null;
  }
}
