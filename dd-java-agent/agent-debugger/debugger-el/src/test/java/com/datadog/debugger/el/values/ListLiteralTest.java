package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.Values;
import org.junit.jupiter.api.Test;

class ListLiteralTest {
  @Test
  void testNullLiteral() {
    checkNullLiteral(null);
    checkNullLiteral(Values.NULL_OBJECT);
    checkNullLiteral(Value.nullValue());
  }

  @Test
  void testUndefinedLiteral() {
    checkUndefinedLiteral(Values.UNDEFINED_OBJECT);
    checkUndefinedLiteral(Value.undefinedValue());
  }

  private void checkNullLiteral(Object nullValue) {
    ListValue literal = new ListValue(nullValue);
    assertTrue(literal.isNull());
    assertTrue(literal.isEmpty());
    assertFalse(literal.isUndefined());
    assertEquals(String.valueOf((Object) null), print(literal));
  }

  private void checkUndefinedLiteral(Object undefinedValue) {
    ListValue literal = new ListValue(undefinedValue);
    assertFalse(literal.isNull());
    assertTrue(literal.isEmpty());
    assertTrue(literal.isUndefined());
    assertEquals(String.valueOf((Object) null), print(literal));
  }
}
