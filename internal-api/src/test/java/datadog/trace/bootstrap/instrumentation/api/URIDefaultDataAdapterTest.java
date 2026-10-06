package datadog.trace.bootstrap.instrumentation.api;

import java.net.URI;

class URIDefaultDataAdapterTest extends URIDataAdapterTest {

  @Override
  URIDataAdapter adapter(URI uri) {
    return new URIDefaultDataAdapter(uri);
  }
}
