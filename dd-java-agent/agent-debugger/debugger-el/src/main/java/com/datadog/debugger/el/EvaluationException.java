package com.datadog.debugger.el;

import java.util.Objects;

public class EvaluationException extends RuntimeException {
  private final String expr;

  public EvaluationException(String message, String expr) {
    super(message);
    this.expr = expr;
  }

  public EvaluationException(String message, String expr, Throwable cause) {
    super(message, cause);
    this.expr = expr;
  }

  public String getExpr() {
    return expr;
  }

  @Override
  public final boolean equals(Object o) {
    if (!(o instanceof EvaluationException)) {
      return false;
    }

    EvaluationException that = (EvaluationException) o;
    return Objects.equals(getExpr(), that.getExpr())
        && Objects.equals(getMessage(), that.getMessage());
  }

  @Override
  public int hashCode() {
    int result = Objects.hashCode(getExpr());
    result = 31 * result + Objects.hashCode(getMessage());
    return result;
  }
}
