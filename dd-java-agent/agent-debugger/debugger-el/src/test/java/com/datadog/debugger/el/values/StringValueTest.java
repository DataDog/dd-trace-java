package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StringValueTest {
  @Test
  void testNullLiteral() {
    StringValue instance = new StringValue(null);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertFalse(instance.isEmpty());
    assertNull(instance.getValue());
    assertEquals(-1, instance.length());
    assertEquals("\"null\"", print(instance));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "hello"})
  void testNonNullLiteral(String expected) {
    StringValue instance = new StringValue(expected);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected.isEmpty(), instance.isEmpty());
    assertEquals(expected, instance.getValue());
    assertEquals(expected.length(), instance.length());
    assertEquals("\"" + expected + "\"", print(instance));
  }
}
