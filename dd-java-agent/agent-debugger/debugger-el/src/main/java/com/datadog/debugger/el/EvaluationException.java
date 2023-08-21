package com.datadog.debugger.el;

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
}
