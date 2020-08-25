package datadog.trace.instrumentation.play23;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import play.api.mvc.Request;

final class RequestURIDataAdapter implements URIDataAdapter {

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
  public String path() {
    int split = request.uri().lastIndexOf('?');
    return split == -1 ? request.uri() : request.uri().substring(0, split);
  }

  @Override
  public String fragment() {
    return "";
  }

  @Override
  public String query() {
    return request.rawQueryString();
  }
}
