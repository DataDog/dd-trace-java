package datadog.trace.bootstrap.instrumentation.api;

import java.net.URI;

class URINoRawDataAdapterTest extends URIDataAdapterTest {

  @Override
  URIDataAdapter adapter(URI uri) {
    return new NoRawTestAdapter(uri);
  }

  @Override
  boolean supportsRaw() {
    return false;
  }

  static class NoRawTestAdapter extends URIDataAdapterBase {
    private final URI uri;

    NoRawTestAdapter(URI uri) {
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
    public String fragment() {
      return uri.getFragment();
    }

    @Override
    public String path() {
      return uri.getPath();
    }

    @Override
    public String query() {
      return uri.getQuery();
    }

    @Override
    public boolean supportsRaw() {
      return false;
    }

    @Override
    public String rawPath() {
      return null;
    }

    @Override
    public String rawQuery() {
      return null;
    }
  }
}
