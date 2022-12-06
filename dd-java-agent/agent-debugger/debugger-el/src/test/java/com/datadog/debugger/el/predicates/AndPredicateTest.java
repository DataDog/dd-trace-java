package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import org.junit.jupiter.api.Test;

class AndPredicateTest {
  @Test
  void testPredicate() {
    for (boolean left : new boolean[] {false, true}) {
      for (boolean right : new boolean[] {false, true}) {
        AndPredicate predicate = new AndPredicate(DSL.value(left), DSL.value(right));
        assertEquals((left && right), predicate.test());
      }
    }

    assertFalse(new AndPredicate(DSL.value(true), null).test());
    assertFalse(new AndPredicate(null, DSL.value(true)).test());
  }
}
