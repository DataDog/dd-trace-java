package datadog.trace.instrumentation.okhttp2;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.okhttp.Headers;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AppSecHeadersTest {
  @Test
  void handlesNullAndEmptyHeaders() {
    assertTrue(AppSecInterceptor.mapHeaders(null).isEmpty());
    Map<String, List<String>> empty = AppSecInterceptor.mapHeaders(new Headers.Builder().build());
    assertTrue(empty.isEmpty());
    empty.put("added", singletonList("value"));
    assertEquals(singletonList("value"), empty.get("added"));
  }

  @Test
  void preservesFirstSpellingValueOrderAndCaseSensitiveLookup() {
    Headers headers =
        new Headers.Builder()
            .add("X-Example", "one")
            .add("Other", "")
            .add("x-example", "two")
            .add("X-EXAMPLE", "three")
            .build();
    Map<String, List<String>> result = AppSecInterceptor.mapHeaders(headers);
    assertEquals(2, result.size());
    assertEquals(asList("one", "two", "three"), result.get("X-Example"));
    assertEquals(singletonList(""), result.get("Other"));
    assertFalse(result.containsKey("x-example"));
    assertThrows(UnsupportedOperationException.class, () -> result.get("X-Example").add("four"));
    result.put("x-example", singletonList("separate"));
    assertEquals(3, result.size());
  }

  @Test
  void matchesExistingConversionAcrossHeaderDistributions() {
    Random random = new Random(937);
    for (int sample = 0; sample < 200; sample++) {
      Headers.Builder builder = new Headers.Builder();
      for (int i = 0, size = random.nextInt(65); i < size; i++) {
        String name = "X-Header-" + random.nextInt(24);
        if (random.nextBoolean()) {
          name = name.toLowerCase(Locale.ROOT);
        }
        builder.add(name, "value-" + i);
      }
      Headers headers = builder.build();
      Map<String, List<String>> expected = new HashMap<>();
      for (String name : headers.names()) {
        expected.put(name, headers.values(name));
      }
      assertEquals(expected, AppSecInterceptor.mapHeaders(headers), "sample " + sample);
    }
  }
}
