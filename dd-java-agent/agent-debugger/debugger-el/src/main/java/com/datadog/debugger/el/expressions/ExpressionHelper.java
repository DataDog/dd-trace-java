package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.PrettyPrintVisitor;

public class ExpressionHelper {
  public static void throwRedactedException(Expression<?> expr) {
    String strExpr = PrettyPrintVisitor.print(expr);
    throw new EvaluationException(
        "Could not evaluate the expression because '" + strExpr + "' was redacted", strExpr);
  }
}
