package datadog.trace.instrumentation.weblogic.t3;

import static java.util.Arrays.asList;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
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
    assertEquals(asList("traceparent", "example", "baggage"), new ArrayList<>(decoded.keySet()));
  }

  @Test
  void omitsOversizedBaggageAndPreservesTraceFields() throws Exception {
    String traceparent = "00-11111111111111111111111111111111-2222222222222222-01";
    char[] oversized = new char[7 * 1024];
    fill(oversized, 'x');
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("baggage", "item=" + new String(oversized));
    fields.put("traceparent", traceparent);
    assertEquals(
        singletonMap("traceparent", traceparent),
        T3PropagationFields.decode(T3PropagationFields.encode(fields)));
  }

  @Test
  void rejectsTruncatedAndOversizedCarriers() throws Exception {
    assertNull(T3PropagationFields.encode(emptyMap()));
    assertTrue(T3PropagationFields.decode("!").isEmpty());
    byte[] truncated = {0, 1, 'k', 0, 5, 'x'};
    assertTrue(T3PropagationFields.decode(Base64.getEncoder().encodeToString(truncated)).isEmpty());
    char[] oversized = new char[8193];
    fill(oversized, 'x');
    assertNull(T3PropagationFields.encode(singletonMap("key", new String(oversized))));
    assertTrue(T3PropagationFields.decode(new String(oversized)).isEmpty());
  }
}
