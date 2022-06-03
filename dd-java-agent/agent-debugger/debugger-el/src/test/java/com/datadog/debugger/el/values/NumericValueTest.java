package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NumericValueTest {
  @Test
  void testNullLiteral() {
    NumericValue instance = new NumericValue(null);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertFalse(instance.test());

    assertNull(instance.getValue());
  }

  @Test
  void testByteLiteral() {
    byte expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());

    assertNotEquals(expected, instance.getValue());
    assertEquals((long) expected, instance.getValue());
  }

  @Test
  void testShortLiteral() {
    short expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());

    assertNotEquals(expected, instance.getValue());
    assertEquals((long) expected, instance.getValue());
  }

  @Test
  void testIntLiteral() {
    int expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());

    assertNotEquals(expected, instance.getValue());
    assertEquals((long) expected, instance.getValue());
  }

  @Test
  void testLongLiteral() {
    long expected = 1;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());

    assertEquals(expected, instance.getValue());
  }

  @Test
  void testFloatLiteral() {
    float expected = 1.0f;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());

    assertEquals((double) expected, instance.getValue());
  }

  @Test
  void testDoubleLiteral() {
    double expected = 1.0;
    NumericValue instance = new NumericValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());

    assertEquals(expected, instance.getValue());
  }
}
