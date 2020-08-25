package datadog.trace.instrumentation.grizzly;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import org.glassfish.grizzly.http.server.Request;

final class RequestURIDataAdapter implements URIDataAdapter {

  private final Request request;

  RequestURIDataAdapter(Request request) {
    this.request = request;
  }

  @Override
  public String scheme() {
    return request.getScheme();
  }

  @Override
  public String host() {
    return request.getServerName();
  }

  @Override
  public int port() {
    return request.getServerPort();
  }

  @Override
  public String path() {
    return request.getRequestURI();
  }

  @Override
  public String fragment() {
    return "";
  }

  @Override
  public String query() {
    return request.getQueryString();
  }
}
