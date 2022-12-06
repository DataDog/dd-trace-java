package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.StaticValueRefResolver;
import com.datadog.debugger.el.values.BooleanValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import org.junit.jupiter.api.Test;

class IfElseExpressionTest {
  private boolean guardFlag = false;

  @Test
  void testIfTrue() {
    boolean[] executed = new boolean[] {false, false};
    PredicateExpression test = PredicateExpression.TRUE;
    Expression<Void> thenExpression =
        context -> {
          executed[0] = true;
          return null;
        };
    Expression<Void> elseExpression =
        context -> {
          executed[1] = true;
          return null;
        };
    DSL.doif(test, thenExpression, elseExpression).evaluate(StaticValueRefResolver.self(this));
    assertTrue(executed[0]);
    assertFalse(executed[1]);
  }

  @Test
  void testIfFalse() {
    boolean[] executed = new boolean[] {false, false};
    PredicateExpression test = PredicateExpression.FALSE;
    Expression<Void> thenExpression =
        context -> {
          executed[0] = true;
          return null;
        };
    Expression<Void> elseExpression =
        context -> {
          executed[1] = true;
          return null;
        };
    DSL.doif(test, thenExpression, elseExpression).evaluate(StaticValueRefResolver.self(this));
    assertFalse(executed[0]);
    assertTrue(executed[1]);
  }

  @Test
  void testFromContext() {
    boolean[] executed = new boolean[] {false, false};
    PredicateExpression test = DSL.eq(DSL.ref(".guardFlag"), BooleanValue.TRUE);
    Expression<Void> thenExpression =
        context -> {
          executed[0] = true;
          return null;
        };
    Expression<Void> elseExpression =
        context -> {
          executed[1] = true;
          return null;
        };
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    guardFlag = false;
    DSL.doif(test, thenExpression, elseExpression).evaluate(ctx);
    assertFalse(executed[0]);
    assertTrue(executed[1]);

    executed[1] = false;

    guardFlag = true;
    DSL.doif(test, thenExpression, elseExpression).evaluate(ctx);
    assertTrue(executed[0]);
    assertFalse(executed[1]);
  }
}
