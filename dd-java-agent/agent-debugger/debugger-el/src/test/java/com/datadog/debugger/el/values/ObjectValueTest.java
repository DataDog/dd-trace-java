package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.debugger.el.Values;
import org.junit.jupiter.api.Test;

class ObjectValueTest {
  @Test
  void testNullLiteral() {
    ObjectValue instance = new ObjectValue(null);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertFalse(instance.test());

    assertEquals(Values.NULL_OBJECT, instance.getValue());

    instance = new ObjectValue(Values.NULL_OBJECT);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertFalse(instance.test());

    assertEquals(Values.NULL_OBJECT, instance.getValue());
  }

  @Test
  void testNonNullLiteral() {
    Object expected = new Object();
    ObjectValue instance = new ObjectValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertTrue(instance.test());

    assertEquals(expected, instance.getValue());
  }

  @Test
  void testUndefinedLiteral() {
    Object expected = Values.UNDEFINED_OBJECT;
    ObjectValue instance = new ObjectValue(expected);
    assertFalse(instance.isNull());
    assertTrue(instance.isUndefined());
    assertTrue(instance.test());

    assertEquals(expected, instance.getValue());
  }
}
