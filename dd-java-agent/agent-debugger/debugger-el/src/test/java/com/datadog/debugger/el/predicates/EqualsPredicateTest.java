package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import org.junit.jupiter.api.Test;

class EqualsPredicateTest {
  @Test
  void equalsSelf() {
    assertFalse(new EqualsPredicate(Value.undefinedValue(), Value.undefinedValue()).test());
    assertTrue(new EqualsPredicate(Value.nullValue(), Value.nullValue()).test());
    Value<?> value = Value.of("a");
    assertTrue(new EqualsPredicate(value, value).test());
  }

  @Test
  void equalsUndefined() {
    Value<?> value = Value.of("a");
    testCommutative(value, Value.undefinedValue(), false);
  }

  @Test
  void equalsNull() {
    Value<?> value = Value.of("a");
    testCommutative(value, Value.nullValue(), false);
  }

  @Test
  void equalsMismatch() {
    Value<?> value1 = Value.of("a");
    Value<?> value2 = Value.of(1);
    testCommutative(value1, value2, false);
  }

  @Test
  void equals() {
    assertTrue(new EqualsPredicate(Value.of("a"), Value.of("a")).test());
  }

  void testCommutative(Value<?> v1, Value<?> v2, boolean expected) {
    assertEquals(expected, new EqualsPredicate(v1, v2).test());
    assertEquals(expected, new EqualsPredicate(v2, v1).test());
  }
}
