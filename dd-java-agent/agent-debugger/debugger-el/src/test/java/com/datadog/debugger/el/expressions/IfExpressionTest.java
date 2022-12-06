package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.StaticValueRefResolver;
import com.datadog.debugger.el.values.BooleanValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import org.junit.jupiter.api.Test;

class IfExpressionTest {
  private boolean guardFlag = false;

  @Test
  void testIfTrue() {
    boolean[] executed = new boolean[] {false};
    PredicateExpression test = PredicateExpression.TRUE;
    Expression<Void> expression =
        context -> {
          executed[0] = true;
          return null;
        };
    DSL.doif(test, expression).evaluate(StaticValueRefResolver.self(this));
    assertTrue(executed[0]);
  }

  @Test
  void testIfFalse() {
    boolean[] executed = new boolean[] {false};
    PredicateExpression test = PredicateExpression.FALSE;
    Expression<Void> expression =
        context -> {
          executed[0] = true;
          return null;
        };
    DSL.doif(test, expression).evaluate(StaticValueRefResolver.self(this));
    assertFalse(executed[0]);
  }

  @Test
  void testFromContext() {
    boolean[] executed = new boolean[] {false};
    PredicateExpression test = DSL.eq(DSL.ref(".guardFlag"), BooleanValue.TRUE);
    Expression<Void> expression =
        context -> {
          executed[0] = true;
          return null;
        };
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    guardFlag = false;
    DSL.doif(test, expression).evaluate(ctx);
    assertFalse(executed[0]);

    guardFlag = true;
    DSL.doif(test, expression).evaluate(ctx);
    assertTrue(executed[0]);
  }
}
