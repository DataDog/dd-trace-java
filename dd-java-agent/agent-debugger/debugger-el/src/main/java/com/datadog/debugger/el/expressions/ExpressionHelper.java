package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.RedactedException;

public class ExpressionHelper {
  public static void throwRedactedException(Expression<?> expr) {
    String strExpr = PrettyPrintVisitor.print(expr);
    throw new RedactedException(
        "Could not evaluate the expression because '" + strExpr + "' was redacted", strExpr);
  }
}
