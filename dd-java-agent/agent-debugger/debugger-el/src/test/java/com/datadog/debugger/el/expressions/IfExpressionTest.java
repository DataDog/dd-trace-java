package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.BooleanValue;
import org.junit.jupiter.api.Test;

class IfExpressionTest {
  private boolean guardFlag = false;

  @Test
  void testIfTrue() {
    boolean[] executed = new boolean[] {false};
    BooleanExpression test = BooleanExpression.TRUE;
    Expression<Void> expression =
        context -> {
          executed[0] = true;
          return null;
        };
    DSL.doif(test, expression).evaluate(RefResolverHelper.createResolver(this));
    assertTrue(executed[0]);
  }

  @Test
  void testIfFalse() {
    boolean[] executed = new boolean[] {false};
    BooleanExpression test = BooleanExpression.FALSE;
    Expression<Void> expression =
        context -> {
          executed[0] = true;
          return null;
        };
    DSL.doif(test, expression).evaluate(RefResolverHelper.createResolver(this));
    assertFalse(executed[0]);
  }

  @Test
  void testFromContext() {
    boolean[] executed = new boolean[] {false};
    BooleanExpression test = DSL.eq(DSL.ref("guardFlag"), BooleanValue.TRUE);
    Expression<Void> expression =
        context -> {
          executed[0] = true;
          return null;
        };
    guardFlag = false;
    DSL.doif(test, expression).evaluate(RefResolverHelper.createResolver(this));
    assertFalse(executed[0]);

    guardFlag = true;
    DSL.doif(test, expression).evaluate(RefResolverHelper.createResolver(this));
    assertTrue(executed[0]);
  }
}
