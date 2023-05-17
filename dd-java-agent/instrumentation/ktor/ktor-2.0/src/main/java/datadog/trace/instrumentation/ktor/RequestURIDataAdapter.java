package datadog.trace.instrumentation.ktor;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import io.ktor.http.Parameters;
import io.ktor.http.RequestConnectionPoint;
import io.ktor.server.request.ApplicationRequest;

final class RequestURIDataAdapter extends URIRawDataAdapter {
  private final RequestConnectionPoint uri;
  private final Parameters queryParams;

  public RequestURIDataAdapter(ApplicationRequest request) {
    this.uri = request.getLocal();
    this.queryParams = request.getRawQueryParameters();
  }

  @Override
  public String scheme() {
    return uri.getScheme();
  }

  @Override
  public String host() {
    return uri.getHost();
  }

  @Override
  public int port() {
    return uri.getPort();
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  protected String innerRawPath() {
    // TODO it may include query params, how to exclude them?
    return uri.getUri();
  }

  @Override
  protected String innerRawQuery() {
    // TODO rebuild rawQuery out of this.queryParams, or find the way to get it in a raw form
    return null;
  }
}
