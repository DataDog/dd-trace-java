package datadog.trace.instrumentation.tomcat;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.apache.tomcat.util.http.MimeHeaders;
import org.junit.jupiter.api.Test;

class ExtractAdapterTest {

  /** Reads the headers straight from the carrier, as the request and response adapters do. */
  private static final ExtractAdapter<MimeHeaders> ADAPTER =
      new ExtractAdapter<MimeHeaders>() {
        @Override
        MimeHeaders getMimeHeaders(MimeHeaders headers) {
          return headers;
        }
      };

  @Test
  void skipsSlotsWithoutAName() {
    MimeHeaders headers = new MimeHeaders();
    headers.addValue("x-first").setString("1");
    headers.addValue("x-empty").setString("2");
    headers.addValue("x-last").setString("3");
    // a slot whose name holds nothing, which reads back as a null name
    headers.getName(1).recycle();
    assertNull(ExtractAdapter.messageBytesToString(headers.getName(1)));

    List<String> keys = new ArrayList<>();
    ADAPTER.forEachKey(
        headers,
        (key, value) -> {
          keys.add(key + "=" + value);
          return true;
        });

    assertEquals(asList("x-first=1", "x-last=3"), keys);
  }

  @Test
  void stopsWhenTheClassifierDoes() {
    MimeHeaders headers = new MimeHeaders();
    headers.addValue("x-first").setString("1");
    headers.addValue("x-second").setString("2");

    List<String> keys = new ArrayList<>();
    ADAPTER.forEachKey(
        headers,
        (key, value) -> {
          keys.add(key);
          return false;
        });

    assertEquals(asList("x-first"), keys);
  }
}
