package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import org.junit.jupiter.api.Test;

class GreaterThanPredicateTest {
  @Test
  void testPredicate() {
    assertTrue(new GreaterThanPredicate(DSL.value(10), DSL.value(1)).test());
    assertFalse(new GreaterThanPredicate(DSL.value(1), DSL.value(10)).test());
    assertFalse(new GreaterThanPredicate(DSL.value(10), DSL.value(10)).test());
  }
}
