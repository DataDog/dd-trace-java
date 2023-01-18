package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NumericValueTest {
  @Test
  void testNullLiteral() {
    NumericValue instance = new NumericValue(null);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertNull(instance.getValue());
    assertEquals("null", instance.prettyPrint());
  }

  @Test
  void testByteLiteral() {
    byte expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertNotEquals(expected, instance.getValue());
    assertEquals((long) expected, instance.getValue());
    assertEquals("1", instance.prettyPrint());
  }

  @Test
  void testShortLiteral() {
    short expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertNotEquals(expected, instance.getValue());
    assertEquals((long) expected, instance.getValue());
    assertEquals("1", instance.prettyPrint());
  }

  @Test
  void testIntLiteral() {
    int expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertNotEquals(expected, instance.getValue());
    assertEquals((long) expected, instance.getValue());
    assertEquals("1", instance.prettyPrint());
  }

  @Test
  void testLongLiteral() {
    long expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1", instance.prettyPrint());
  }

  @Test
  void testFloatLiteral() {
    float expected = 1.0f;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals((double) expected, instance.getValue());
    assertEquals("1.0", instance.prettyPrint());
  }

  @Test
  void testDoubleLiteral() {
    double expected = 1.0;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1.0", instance.prettyPrint());
  }
}
