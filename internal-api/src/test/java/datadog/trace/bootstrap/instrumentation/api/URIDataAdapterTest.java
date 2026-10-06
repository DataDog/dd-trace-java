package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.tabletest.junit.TableTest;

abstract class URIDataAdapterTest {

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
