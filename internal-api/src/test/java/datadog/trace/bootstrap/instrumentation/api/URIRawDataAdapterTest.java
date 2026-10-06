package datadog.trace.bootstrap.instrumentation.api;

import java.net.URI;

class URIRawDataAdapterTest extends URIDataAdapterTest {

  @Override
  URIDataAdapter adapter(URI uri) {
    return new RawTestAdapter(uri);
  }

  static class RawTestAdapter extends URIRawDataAdapter {
    private final URI uri;

    RawTestAdapter(URI uri) {
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
    protected String innerRawPath() {
      return uri.getRawPath();
    }

    @Override
    protected String innerRawQuery() {
      return uri.getRawQuery();
    }
  }
}
