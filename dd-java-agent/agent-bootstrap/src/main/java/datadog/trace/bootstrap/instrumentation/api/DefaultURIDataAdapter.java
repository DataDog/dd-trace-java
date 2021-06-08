package datadog.trace.bootstrap.instrumentation.api;

import java.net.URI;

public class DefaultURIDataAdapter implements URIDataAdapter {

  private final URI uri;

  public DefaultURIDataAdapter(URI uri) {
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
}
