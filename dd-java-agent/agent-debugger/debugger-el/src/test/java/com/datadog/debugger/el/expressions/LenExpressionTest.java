package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class LenExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  @Test
  void nullExpression() {
    LenExpression expression = new LenExpression(null);
    assertEquals(-1L, expression.evaluate(resolver).getValue());
    assertEquals("len(null)", print(expression));
  }

  @Test
  void undefinedExpression() {
    LenExpression expression = new LenExpression(DSL.value(Values.UNDEFINED_OBJECT));
    assertEquals(0L, expression.evaluate(resolver).getValue());
    assertEquals("len(UNDEFINED)", print(expression));
  }

  @Test
  void stringExpression() {
    LenExpression expression = new LenExpression(DSL.value("a"));
    assertEquals(1L, expression.evaluate(resolver).getValue());
    assertEquals("len(\"a\")", print(expression));
  }

  @Test
  void collectionExpression() {
    LenExpression expression = new LenExpression(DSL.value(Arrays.asList("a", "b")));
    assertEquals(2L, expression.evaluate(resolver).getValue());
    assertEquals("len(List)", print(expression));
    expression = new LenExpression(DSL.value(new HashSet<>(Arrays.asList("a", "b"))));
    assertEquals(2L, expression.evaluate(resolver).getValue());
    assertEquals("len(Set)", print(expression));
  }

  @Test
  void mapExpression() {
    LenExpression expression = new LenExpression(DSL.value(Collections.singletonMap("a", "b")));
    assertEquals(1L, expression.evaluate(resolver).getValue());
    assertEquals("len(Map)", print(expression));
  }
}
