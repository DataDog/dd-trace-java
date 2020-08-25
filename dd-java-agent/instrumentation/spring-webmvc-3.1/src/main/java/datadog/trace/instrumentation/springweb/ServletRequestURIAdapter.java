package datadog.trace.instrumentation.springweb;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import javax.servlet.http.HttpServletRequest;

public final class ServletRequestURIAdapter implements URIDataAdapter {
  private final HttpServletRequest request;

  public ServletRequestURIAdapter(HttpServletRequest request) {
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
