package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.ValueType;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class NumericValueTest {
  @Test
  void testNullLiteral() {
    NumericValue instance = new NumericValue(null, ValueType.OBJECT);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertNull(instance.getValue());
    assertEquals("null", print(instance));
  }

  @Test
  void testByteLiteral() {
    byte expected = 1;
    NumericValue instance = new NumericValue(expected, ValueType.BYTE);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1", print(instance));
  }

  @Test
  void testShortLiteral() {
    short expected = 1;
    NumericValue instance = new NumericValue(expected, ValueType.SHORT);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1", print(instance));
  }

  @Test
  void testIntLiteral() {
    int expected = 1;
    NumericValue instance = new NumericValue(expected, ValueType.INT);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1", print(instance));
  }

  @Test
  void testLongLiteral() {
    long expected = 1;
    NumericValue instance = new NumericValue(expected, ValueType.LONG);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1", print(instance));
  }

  @Test
  void testFloatLiteral() {
    float expected = 1.0f;
    NumericValue instance = new NumericValue(expected, ValueType.FLOAT);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1.0", print(instance));
  }

  @Test
  void testDoubleLiteral() {
    double expected = 1.0;
    NumericValue instance = new NumericValue(expected, ValueType.DOUBLE);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1.0", print(instance));
  }

  @Test
  void testBigDecimalLiteral() {
    BigDecimal expected = new BigDecimal("1.0");
    NumericValue instance = new NumericValue(expected, ValueType.OBJECT);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1.0", print(instance));
  }

  @Test
  void testBigIntegerLiteral() {
    BigInteger expected = new BigInteger("1234567890");
    NumericValue instance = new NumericValue(expected, ValueType.OBJECT);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals("1234567890", print(instance));
  }
}
