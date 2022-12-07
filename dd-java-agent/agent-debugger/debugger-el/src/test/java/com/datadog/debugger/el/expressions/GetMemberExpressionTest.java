package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
import org.junit.jupiter.api.Test;

class GetMemberExpressionTest {

  @Test
  void getMemberLevel1() {
    GetMemberExpression expr = new GetMemberExpression(new ValueRefExpression("ref"), "b");
    Tester parent = new Tester(null, "hello");
    ExTester instance = new ExTester(parent, "world");
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(instance));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals(parent.getB(), val.getValue());
  }

  @Test
  void getMemberLevel2() {
    // ref.ref.b
    GetMemberExpression expr =
        new GetMemberExpression(new GetMemberExpression(new ValueRefExpression("ref"), "ref"), "b");
    Tester root = new Tester(null, "hello");
    Tester parent = new Tester(root, "");
    ExTester instance = new ExTester(parent, "world");
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(instance));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals(root.getB(), val.getValue());
  }
}
