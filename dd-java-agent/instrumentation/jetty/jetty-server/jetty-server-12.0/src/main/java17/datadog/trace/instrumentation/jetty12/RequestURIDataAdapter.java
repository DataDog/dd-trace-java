package datadog.trace.instrumentation.jetty12;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import org.eclipse.jetty.server.Request;

final class RequestURIDataAdapter extends URIRawDataAdapter {

  private final Request request;

  RequestURIDataAdapter(Request request) {
    this.request = request;
  }

  @Override
  public String scheme() {
    return request.getHttpURI().getScheme();
  }

  @Override
  public String host() {
    return Request.getServerName(request);
  }

  @Override
  public int port() {
    return Request.getServerPort(request);
  }

  @Override
  protected String innerRawPath() {
    return Request.getPathInContext(request);
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  protected String innerRawQuery() {
    return request.getHttpURI().getQuery();
  }
}
