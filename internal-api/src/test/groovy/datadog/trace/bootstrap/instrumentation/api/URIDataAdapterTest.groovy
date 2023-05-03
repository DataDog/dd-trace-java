package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.test.util.DDSpecification
import spock.lang.Unroll

abstract class URIDataAdapterTest extends DDSpecification {

  abstract URIDataAdapter adapter(URI uri)

  boolean supportsRaw() {
    return true
  }

  @Unroll
  def "test URI parts #input"() {
    setup:
    def adapter = URIDataAdapterBase.fromURI(input, {adapter(it)})

    expect:
    adapter.scheme() == scheme
    adapter.host() == host
    adapter.port() == port
    adapter.path() == path
    adapter.fragment() == fragment
    adapter.query() == query
    adapter.supportsRaw() == supportsRaw()
    adapter.rawPath() == (supportsRaw() ? rawPath : null)
    adapter.rawQuery() == (supportsRaw() ? rawQuery : null)
    adapter.raw() == (supportsRaw() ? raw : null)
    adapter.isValid()

    where:
    // spotless:off
    input                                | scheme  |  host  | port | path    | fragment   | query   | rawPath     | rawQuery     | raw
    "http://host:17/path?query#fragment" | "http"  | "host" | 17   | "/path" | "fragment" | "query" | "/path"     | "query"      | "/path?query"
    "https://h0st"                       | "https" | "h0st" | -1   | ""      | null       | null    | ""          | null         | ""
    "http://host/v%C3%A4g?fr%C3%A5ga"    | "http"  | "host" | -1   | "/väg"  | null       | "fråga" | "/v%C3%A4g" | "fr%C3%A5ga" | "/v%C3%A4g?fr%C3%A5ga"
    // spotless:on
  }
}

class URIDefaultDataAdapterTest extends URIDataAdapterTest {
  @Override
  URIDataAdapter adapter(URI uri) {
    return new URIDefaultDataAdapter(uri)
  }
}

class URIRawDataAdapterTest extends URIDataAdapterTest {

  @Override
  URIDataAdapter adapter(URI uri) {
    return new RawTestAdapter(uri)
  }

  static class RawTestAdapter extends URIRawDataAdapter {
    private final URI uri

    RawTestAdapter(URI uri) {
      this.uri = uri
    }

    @Override
    String scheme() {
      return uri.scheme
    }

    @Override
    String host() {
      return uri.host
    }

    @Override
    int port() {
      return uri.port
    }

    @Override
    String fragment() {
      return uri.fragment
    }

    @Override
    protected String innerRawPath() {
      return uri.rawPath
    }

    @Override
    protected String innerRawQuery() {
      return uri.rawQuery
    }
  }
}

class URINoRawDataAdapterTest extends URIDataAdapterTest {

  @Override
  URIDataAdapter adapter(URI uri) {
    return new NoRawTestAdapter(uri)
  }

  @Override
  boolean supportsRaw() {
    return false
  }

  static class NoRawTestAdapter extends URIDataAdapterBase {
    private final URI uri

    NoRawTestAdapter(URI uri) {
      this.uri = uri
    }

    @Override
    String scheme() {
      return uri.scheme
    }

    @Override
    String host() {
      return uri.host
    }

    @Override
    int port() {
      return uri.port
    }

    @Override
    String fragment() {
      return uri.fragment
    }

    @Override
    String path() {
      return uri.path
    }

    @Override
    String query() {
      return uri.query
    }

    @Override
    boolean supportsRaw() {
      return false
    }

    @Override
    String rawPath() {
      return null
    }

    @Override
    String rawQuery() {
      return null
    }
  }

  static class UnparseableURIAdapterTest extends DDSpecification {
    def "should return raw URI only"() {
      setup:
      def uriStr = "http://myurl/path?query=value#fragment"
      def uriAdapter = new UnparseableURIDataAdapter(uriStr)

      expect:
      !uriAdapter.isValid()
      uriAdapter.host() == null
      uriAdapter.port() == 0
      uriAdapter.fragment() == null
      uriAdapter.path() == null
      uriAdapter.path() == null
      uriAdapter.query() == null
      uriAdapter.rawQuery() == null
      uriAdapter.scheme() == null
      uriAdapter.rawPath() == null
      uriAdapter.raw() == uriStr
    }
  }
}
