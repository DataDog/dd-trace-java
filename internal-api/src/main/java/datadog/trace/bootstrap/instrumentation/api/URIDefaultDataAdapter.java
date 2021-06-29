package datadog.trace.bootstrap.instrumentation.api;

import java.net.URI;

public class URIDefaultDataAdapter extends URIDataAdapterBase {

  private final URI uri;

  public URIDefaultDataAdapter(URI uri) {
    this.uri = uri;
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
  public String path() {
    return uri.getPath();
  }

  @Override
  public String fragment() {
    return uri.getFragment();
  }

  @Override
  public String query() {
    return uri.getQuery();
  }

  @Override
  public boolean supportsRaw() {
    return true;
  }

  @Override
  public String rawPath() {
    return uri.getRawPath();
  }

  @Override
  public String rawQuery() {
    return uri.getRawQuery();
  }
}
