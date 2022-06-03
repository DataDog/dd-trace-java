package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import org.junit.jupiter.api.Test;

class GreaterOrEqualPredicateTest {
  @Test
  void testPredicate() {
    assertTrue(new GreaterOrEqualPredicate(DSL.value(10), DSL.value(1)).test());
    assertFalse(new GreaterOrEqualPredicate(DSL.value(1), DSL.value(10)).test());
    assertTrue(new GreaterOrEqualPredicate(DSL.value(10), DSL.value(10)).test());
  }
}
