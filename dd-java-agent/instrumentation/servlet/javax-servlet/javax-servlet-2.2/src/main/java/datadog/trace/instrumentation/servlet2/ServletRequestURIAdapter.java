package datadog.trace.instrumentation.servlet2;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import javax.servlet.http.HttpServletRequest;

public class ServletRequestURIAdapter extends URIRawDataAdapter {
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
