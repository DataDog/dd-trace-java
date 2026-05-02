package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BooleanValueTest {
  @Test
  void testNullLiteral() {
    BooleanValue instance = new BooleanValue(null, ValueType.OBJECT);
    assertTrue(instance.isNull());
    assertFalse(instance.isUndefined());
    assertNull(instance.getValue());
    assertEquals("null", print(instance));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testNonNullLiteral(boolean expected) {
    BooleanValue instance = new BooleanValue(expected, ValueType.BOOLEAN);
    assertFalse(instance.isNull());
    assertFalse(instance.isUndefined());
    assertEquals(expected, instance.getValue());
    assertEquals(String.valueOf(expected), print(instance));
  }
}
