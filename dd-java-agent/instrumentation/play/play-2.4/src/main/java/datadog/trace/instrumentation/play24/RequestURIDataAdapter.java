package datadog.trace.instrumentation.play24;

import static java.lang.Math.max;

import datadog.trace.bootstrap.instrumentation.api.HostHeader;
import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import play.api.mvc.Request;

final class RequestURIDataAdapter extends URIRawDataAdapter {

  private final Request request;
  private final String host;
  private final int port;

  RequestURIDataAdapter(Request request) {
    this.request = request;
    // the Host header comes from the client, so the port may be missing or malformed
    final String hostHeader = request.host();
    this.host = HostHeader.host(hostHeader);
    this.port = max(HostHeader.port(hostHeader), 0);
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
