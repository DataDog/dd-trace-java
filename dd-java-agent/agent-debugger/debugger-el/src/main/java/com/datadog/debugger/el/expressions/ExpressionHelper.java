package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.RedactedException;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.util.Collection;

public class ExpressionHelper {
  public static final int MAX_COLLECTION_ITEMS = 1_000_000;
  public static final int MAX_ARRAY_ITEMS = 1_000_000;
  public static final int MAX_STRING_LENGTH = 100_000_000;

  public static void throwRedactedException(Expression<?> expr) {
    String strExpr = PrettyPrintVisitor.print(expr);
    throw new RedactedException(
        "Could not evaluate the expression because '" + strExpr + "' was redacted", strExpr);
  }

  public static void checkTimeout(TimeoutChecker checker, Expression<?> expr) {
    if (checker.isTimedOut()) {
      throw new EvaluationException(
          "timeout (" + checker.getTimeOut().toMillis() + "ms)", PrettyPrintVisitor.print(expr));
    }
  }

  public static void checkStringLength(String val, Expression<?> expr) {
    if (val == null) {
      return;
    }
    if (val.length() > MAX_STRING_LENGTH) {
      throw new EvaluationException(
          "string too large (>" + MAX_STRING_LENGTH + ")", PrettyPrintVisitor.print(expr));
    }
  }

  public static void checkCollectionSize(Collection<?> collection, Expression<?> expr) {
    if (collection == null) {
      return;
    }
    if (collection.size() > MAX_COLLECTION_ITEMS) {
      throw new EvaluationException(
          "Collection too large (>" + MAX_COLLECTION_ITEMS + ")", PrettyPrintVisitor.print(expr));
    }
  }

  public static void checkArrayLength(int arrayLength, Expression<?> expr) {
    if (arrayLength > MAX_ARRAY_ITEMS) {
      throw new EvaluationException(
          "Array too large (>" + MAX_ARRAY_ITEMS + ")", PrettyPrintVisitor.print(expr));
    }
  }
}
