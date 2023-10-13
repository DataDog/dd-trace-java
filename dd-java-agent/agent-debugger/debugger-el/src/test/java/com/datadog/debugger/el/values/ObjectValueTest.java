package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.debugger.el.Values;
import org.junit.jupiter.api.Test;

class ObjectValueTest {
  @Test
  void testNullLiteral() {
    ObjectValue instance = new ObjectValue(null);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(Values.NULL_OBJECT, instance.getValue());
    assertEquals("null", print(instance));

    instance = new ObjectValue(Values.NULL_OBJECT);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(Values.NULL_OBJECT, instance.getValue());
    assertEquals("null", print(instance));
  }

  @Test
  void testNonNullLiteral() {
    Object expected = new Object();
    ObjectValue instance = new ObjectValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("java.lang.Object", print(instance));
  }

  @Test
  void testUndefinedLiteral() {
    Object expected = Values.UNDEFINED_OBJECT;
    ObjectValue instance = new ObjectValue(expected);
    assertFalse(instance.isNull());
    assertTrue(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("UNDEFINED", print(instance));
  }
}
