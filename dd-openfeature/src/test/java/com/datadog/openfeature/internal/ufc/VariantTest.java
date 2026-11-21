package com.datadog.openfeature.internal.ufc;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VariantTest {
  @Test
  void aJsonContainerIsFrozenOnFirstUseAndThenShared() {
    final Map<String, Object> tree = singletonMap("a", (Object) singletonList(1));
    final Variant variant = new Variant("on", tree, true);

    final Object first = variant.value();

    assertTrue(first instanceof Value);
    assertEquals(Value.objectToValue(tree), first);
    assertSame(first, variant.value());
  }

  @Test
  void otherValuesAreReturnedAsParsed() {
    final List<Object> tree = singletonList("x");

    assertSame(tree, new Variant("on", tree).value());
    assertEquals("s", new Variant("on", "s", true).value());
    assertEquals(null, new Variant("on", null, true).value());
  }
}
