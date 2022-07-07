package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BooleanValueTest {
  @Test
  void testNullLiteral() {
    BooleanValue instance = new BooleanValue(null);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertFalse(instance.test());

    assertNull(instance.getValue());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testNonNullLiteral(boolean expected) {
    BooleanValue instance = new BooleanValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.test());

    assertEquals(expected, instance.getValue());
  }
}
