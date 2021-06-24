package datadog.trace.instrumentation.jetty76;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import javax.servlet.http.HttpServletRequest;

final class RequestURIDataAdapter extends URIRawDataAdapter {

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
  protected String innerRawPath() {
    return request.getRequestURI();
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  protected String innerRawQuery() {
    return request.getQueryString();
  }
}
