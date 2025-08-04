package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RefResolverHelper;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class SubStringExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
  // used to ref lookup
  URI uri = URI.create("https://www.datadoghq.com");
  Object nullValue = null;

  @Test
  void nullExpression() {
    SubStringExpression expression1 = new SubStringExpression(null, 0, 0);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression1.evaluate(resolver));
    assertEquals("substring(null, 0, 0)", evaluationException.getExpr());
    SubStringExpression expression2 = new SubStringExpression(DSL.ref("nullValue"), 0, 0);
    evaluationException =
        assertThrows(EvaluationException.class, () -> expression2.evaluate(resolver));
    assertEquals("substring(nullValue, 0, 0)", evaluationException.getExpr());
  }

  @Test
  void undefinedExpression() {
    SubStringExpression expression =
        new SubStringExpression(DSL.value(Values.UNDEFINED_OBJECT), 0, 0);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(resolver));
    assertEquals("substring(UNDEFINED, 0, 0)", evaluationException.getExpr());
  }

  @Test
  void stringExpression() {
    SubStringExpression expression = new SubStringExpression(DSL.value("abc"), 0, 1);
    assertEquals("a", expression.evaluate(resolver).getValue());
    assertEquals("substring(\"abc\", 0, 1)", print(expression));
  }

  @Test
  void stringOutOfBoundsExpression() {
    SubStringExpression expression = new SubStringExpression(DSL.value("abc"), 0, 10);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(resolver));
    assertEquals("substring(\"abc\", 0, 10)", evaluationException.getExpr());
  }

  @Test
  void stringPrimitives() {
    SubStringExpression expression = new SubStringExpression(DSL.ref("uri"), 0, 5);
    assertEquals("https", expression.evaluate(resolver).getValue());
    assertEquals("substring(uri, 0, 5)", print(expression));
  }
}
