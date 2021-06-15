package datadog.trace.instrumentation.spray;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import spray.http.Uri;

public final class SprayURIAdapter implements URIDataAdapter {
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
  public String path() {
    return uri.path().toString();
  }

  @Override
  public String fragment() {
    if (uri.fragment().isEmpty()) {
      return "";
    }
    return uri.fragment().get();
  }

  @Override
  public String query() {
    return uri.query().toString();
  }
}
