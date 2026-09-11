package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
  @SuppressFBWarnings(
      value = "DCN_NULLPOINTER_EXCEPTION",
      justification =
          "getHostPort() NPEs inside Undertow itself, not on a null we could check beforehand"
              + " (e.g. no Host header and a connection whose local address isn't an"
              + " InetSocketAddress, such as AJP or a Unix domain socket transport)")
  public int port() {
    try {
      return httpServerExchange.getHostPort();
    } catch (final NullPointerException e) {
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
