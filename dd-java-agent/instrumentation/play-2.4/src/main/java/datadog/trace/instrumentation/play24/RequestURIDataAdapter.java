package datadog.trace.instrumentation.play24;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import play.api.mvc.Request;

final class RequestURIDataAdapter extends URIRawDataAdapter {

  private final Request request;
  private final String host;
  private final int port;

  RequestURIDataAdapter(Request request) {
    this.request = request;
    int split = request.host().lastIndexOf(':');
    this.host = split == -1 ? request.host() : request.host().substring(0, split);
    this.port = split == -1 ? 0 : Integer.parseInt(request.host().substring(split + 1));
  }

  @Override
  public String scheme() {
    return request.secure() ? "https" : "http";
  }

  @Override
  public String host() {
    return host;
  }

  @Override
  public int port() {
    return port;
  }

  @Override
  protected String innerRawPath() {
    return request.path();
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  protected String innerRawQuery() {
    return request.rawQueryString();
  }
}
