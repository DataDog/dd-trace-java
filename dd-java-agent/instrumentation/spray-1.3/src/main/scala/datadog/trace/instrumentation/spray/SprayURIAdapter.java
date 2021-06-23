package datadog.trace.instrumentation.spray;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import spray.http.Uri;

public final class SprayURIAdapter extends URIRawDataAdapter {
  private final Uri uri;

  public SprayURIAdapter(Uri uri) {
    this.uri = uri;
  }

  @Override
  public String scheme() {
    return uri.scheme();
  }

  @Override
  public String host() {
    return uri.authority().host().address();
  }

  @Override
  public int port() {
    return uri.authority().port();
  }

  @Override
  public String fragment() {
    return uri.fragment().isEmpty() ? null : uri.fragment().get();
  }

  @Override
  protected String innerRawPath() {
    return uri.path().toString();
  }

  @Override
  public boolean hasPlusEncodedSpaces() {
    return true;
  }

  @Override
  protected String innerRawQuery() {
    return uri.query().toString();
  }
}
