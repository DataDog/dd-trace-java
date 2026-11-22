package com.datadog.openfeature.internal.flagevaluation;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HashedContextTest {
  @Test
  void keepsTheHashTheAggregatorWouldCompute() {
    final HashedContext context = new HashedContext();
    assertEquals(0L, context.contextHash());

    context.put("plan", "gold");
    context.put("age", 42);
    context.put("beta", true);
    context.put("missing", null);

    assertEquals(walked(context), context.contextHash());
    assertEquals(walked(context), ConcurrentFlagAggregator.contextHash(context));
  }

  @Test
  void followsReplacementsRemovalsAndClears() {
    final HashedContext context = new HashedContext();
    context.putAll(singletonMap("a", (Object) 1));
    context.put("b", "x");
    context.put("a", 1L); // replaced, with another type
    assertEquals(walked(context), context.contextHash());

    assertEquals("x", context.remove("b"));
    assertNull(context.remove("absent"));
    assertEquals(walked(context), context.contextHash());

    context.clear();
    assertEquals(0L, context.contextHash());
  }

  @Test
  void matchesAnEqualPlainMap() {
    final HashedContext hashed = new HashedContext();
    hashed.put("k1", "v");
    hashed.put("k2", 2.5);
    final Map<String, Object> plain = new LinkedHashMap<>();
    plain.put("k2", 2.5);
    plain.put("k1", "v");

    assertEquals(
        ConcurrentFlagAggregator.contextHash(plain), ConcurrentFlagAggregator.contextHash(hashed));
    assertEquals(plain, hashed);
  }

  /** The hash by walking a plain copy, which takes ConcurrentFlagAggregator's other branch. */
  private static long walked(final Map<String, Object> context) {
    return ConcurrentFlagAggregator.contextHash(new HashMap<>(context));
  }
}
