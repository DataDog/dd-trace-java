package datadog.trace.api.gateway.inferredproxy;

import static datadog.context.Context.root;
import static datadog.trace.api.gateway.InferredProxyHeaders.fromContext;
import static datadog.trace.api.gateway.InferredProxyHeaders.fromValues;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.Context;
import datadog.trace.api.gateway.InferredProxyHeaders;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InferredProxyContext Tests")
class InferredProxyHeadersTests {
  @Test
  @DisplayName("Constructor with map should initialize context map")
  void testMapConstructor() {
    Map<String, String> initialData = new HashMap<>();
    initialData.put("key1", "value1");
    initialData.put("key2", "value2");

    InferredProxyHeaders headers = InferredProxyHeaders.fromValues(initialData);
    assertEquals(2, headers.size());
    assertEquals("value1", headers.getValue("key1"));
    assertEquals("value2", headers.getValue("key2"));
  }

  @Test
  @DisplayName("storeInto and fromContext should correctly attach and retrieve the context")
  void testStoreAndFromContext() {
    InferredProxyHeaders inferredProxyHeaders = InferredProxyHeaders.fromValues(null);
    Context context = inferredProxyHeaders.storeInto(root());
    assertNotNull(context);

    InferredProxyHeaders retrieved = fromContext(context);
    assertNotNull(retrieved);

    assertNull(fromContext(root()), "fromContext on empty context should be null");
  }

  @Test
  @DisplayName("Constructor with null map should create an empty context map")
  void testNullMapConstructor() {
    InferredProxyHeaders inferredProxyHeaders = InferredProxyHeaders.fromValues(null);
    assertNotNull(inferredProxyHeaders);
    assertEquals(0, inferredProxyHeaders.size());
  }

  @Test
  @DisplayName("Constructor with empty map should create an empty context map")
  void testEmptyMapConstructor() {
    InferredProxyHeaders inferredProxyHeaders = fromValues(emptyMap());
    assertNotNull(inferredProxyHeaders);
    assertEquals(0, inferredProxyHeaders.size());
  }
}
