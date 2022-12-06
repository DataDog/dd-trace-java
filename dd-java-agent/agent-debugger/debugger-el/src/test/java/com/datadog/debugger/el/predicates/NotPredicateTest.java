package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import org.junit.jupiter.api.Test;

class NotPredicateTest {
  @Test
  void testPredicate() {
    for (boolean value : new boolean[] {false, true}) {
      NotPredicate predicate = new NotPredicate(DSL.value(value));
      assertEquals(!value, predicate.test());
    }
  }
}
