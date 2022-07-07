package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import org.junit.jupiter.api.Test;

class LessThanPredicateTest {
  @Test
  void testPredicate() {
    assertFalse(new LessThanPredicate(DSL.value(10), DSL.value(1)).test());
    assertTrue(new LessThanPredicate(DSL.value(1), DSL.value(10)).test());
    assertFalse(new LessThanPredicate(DSL.value(10), DSL.value(10)).test());
  }
}
