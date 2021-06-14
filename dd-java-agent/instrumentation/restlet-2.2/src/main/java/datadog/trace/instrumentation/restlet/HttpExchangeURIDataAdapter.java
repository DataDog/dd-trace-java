package datadog.trace.instrumentation.restlet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.net.InetSocketAddress;

final class HttpExchangeURIDataAdapter implements URIDataAdapter {

  private final HttpExchange exchange;

  HttpExchangeURIDataAdapter(HttpExchange exchange) {
    this.exchange = exchange;
  }

  @Override
  public String scheme() {
    // scheme is not available in getRequestURI
    if (exchange instanceof HttpsExchange) {
      return "https";
    }
    return "http";
  }

  @Override
  public String host() {
    // host is not available in getRequestURI
    InetSocketAddress inetSocketAddress = exchange.getLocalAddress();
    if (inetSocketAddress.isUnresolved()) {
      return inetSocketAddress.getHostString();
    } else {
      return inetSocketAddress.getHostName();
    }
  }

  @Override
  public int port() {
    // port is not available in getRequestURI
    return exchange.getLocalAddress().getPort();
  }

  @Override
  public String path() {
    return exchange.getRequestURI().getPath();
  }

  @Override
  public String fragment() {
    // fragment is not available in getRequestURI
    return "";
  }

  @Override
  public String query() {
    return exchange.getRequestURI().getQuery();
  }
}
