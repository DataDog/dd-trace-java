package datadog.trace.instrumentation.liberty20;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import javax.servlet.http.HttpServletRequest;

final class RequestURIDataAdapter implements URIDataAdapter {

  private final HttpServletRequest request;

  RequestURIDataAdapter(HttpServletRequest request) {
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
