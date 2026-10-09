package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.tabletest.junit.TableTest;

public class URIDataAdapterTest {
  abstract static class AbstractURIDataAdapterTest {

    abstract URIDataAdapter adapter(URI uri);

    boolean supportsRaw() {
      return true;
    }

    @TableTest({
      "scenario      | input                                | scheme | host | port | path  | fragment | query | rawPath   | rawQuery   | raw                   ",
      "all parts     | 'http://host:17/path?query#fragment' | http   | host | 17   | /path | fragment | query | /path     | query      | /path?query           ",
      "host only     | 'https://h0st'                       | https  | h0st | -1   | ''    |          |       | ''        |            | ''                    ",
      "encoded parts | 'http://host/v%C3%A4g?fr%C3%A5ga'    | http   | host | -1   | /väg  |          | fråga | /v%C3%A4g | fr%C3%A5ga | '/v%C3%A4g?fr%C3%A5ga'"
    })
    void testUriParts(
        String input,
        String scheme,
        String host,
        int port,
        String path,
        String fragment,
        String query,
        String rawPath,
        String rawQuery,
        String raw) {
      URIDataAdapter adapter = URIDataAdapterBase.fromURI(input, this::adapter);

      assertEquals(scheme, adapter.scheme());
      assertEquals(host, adapter.host());
      assertEquals(port, adapter.port());
      assertEquals(path, adapter.path());
      assertEquals(fragment, adapter.fragment());
      assertEquals(query, adapter.query());
      assertEquals(supportsRaw(), adapter.supportsRaw());
      assertEquals(supportsRaw() ? rawPath : null, adapter.rawPath());
      assertEquals(supportsRaw() ? rawQuery : null, adapter.rawQuery());
      assertEquals(supportsRaw() ? raw : null, adapter.raw());
      assertTrue(adapter.isValid());
    }
  }

  static class URIDefaultDataAdapterTest extends AbstractURIDataAdapterTest {

    @Override
    URIDataAdapter adapter(URI uri) {
      return new URIDefaultDataAdapter(uri);
    }
  }

  static class URINoRawDataAdapterTest extends AbstractURIDataAdapterTest {

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

  static class URIRawDataAdapterTest extends AbstractURIDataAdapterTest {

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
}
