package datadog.trace.instrumentation.weblogic.t3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class T3PropagationFieldsTest {
  @Test
  void preservesConfiguredFields() throws Exception {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
    fields.put("baggage", "encoded=a%3Db");
    fields.put("example", "line\n\u0000value=\u03b1");
    Map<String, String> decoded = T3PropagationFields.decode(T3PropagationFields.encode(fields));
    assertEquals(fields, decoded);
    assertEquals(
        Arrays.asList("traceparent", "baggage", "example"),
        Arrays.asList(decoded.keySet().toArray()));
  }

  @Test
  void rejectsTruncatedAndOversizedCarriers() throws Exception {
    assertNull(T3PropagationFields.encode(Collections.emptyMap()));
    assertTrue(T3PropagationFields.decode("!").isEmpty());
    byte[] truncated = {0, 1, 'k', 0, 5, 'x'};
    assertTrue(T3PropagationFields.decode(Base64.getEncoder().encodeToString(truncated)).isEmpty());
    char[] oversized = new char[8193];
    Arrays.fill(oversized, 'x');
    assertNull(T3PropagationFields.encode(Collections.singletonMap("key", new String(oversized))));
    assertTrue(T3PropagationFields.decode(new String(oversized)).isEmpty());
  }
}
