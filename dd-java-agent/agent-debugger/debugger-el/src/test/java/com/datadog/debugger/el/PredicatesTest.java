package com.datadog.debugger.el;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.predicates.AndPredicate;
import com.datadog.debugger.el.predicates.NotPredicate;
import com.datadog.debugger.el.predicates.OrPredicate;
import com.datadog.debugger.el.values.BooleanValue;
import org.junit.jupiter.api.Test;

class PredicatesTest {

  @Test
  void testBooleanLiteral() {
    assertFalse(new BooleanValue(null).test());
    assertFalse(new BooleanValue(false).test());
    assertTrue(new BooleanValue(true).test());
  }

  @Test
  void testNegationSimple() {
    Predicate term = new BooleanValue(true);
    assertFalse(new NotPredicate(term).test());
  }

  @Test
  void testNegationNegation() {
    Predicate term = new BooleanValue(true);
    assertTrue(new NotPredicate(new NotPredicate(term)).test());
  }

  @Test
  void testAddOperation() {
    for (boolean left : new boolean[] {true, false}) {
      for (boolean right : new boolean[] {true, false}) {
        Predicate p1 = new BooleanValue(left);
        Predicate p2 = new BooleanValue(right);
        assertEquals((left && right), new AndPredicate(p1, p2).test());
      }
    }
  }

  @Test
  void testOrOperation() {
    for (boolean left : new boolean[] {true, false}) {
      for (boolean right : new boolean[] {true, false}) {
        Predicate p1 = new BooleanValue(left);
        Predicate p2 = new BooleanValue(right);
        assertEquals((left || right), new OrPredicate(p1, p2).test());
      }
    }
  }

  @Test
  void testNegationOr() {
    Predicate p1 = new BooleanValue(false);
    Predicate p2 = new BooleanValue(true);

    assertFalse(new NotPredicate(new OrPredicate(p1, p2)).test());
  }
}
