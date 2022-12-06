package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import org.junit.jupiter.api.Test;

class OrPredicateTest {
  @Test
  void testPredicate() {
    for (boolean left : new boolean[] {false, true}) {
      for (boolean right : new boolean[] {false, true}) {
        OrPredicate predicate = new OrPredicate(DSL.value(left), DSL.value(right));
        assertEquals((left || right), predicate.test());
      }
    }
    assertFalse(new OrPredicate(DSL.value(false), null).test());
    assertFalse(new OrPredicate(null, DSL.value(false)).test());
  }
}
