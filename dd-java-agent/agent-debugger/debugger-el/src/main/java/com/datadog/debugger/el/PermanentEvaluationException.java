package com.datadog.debugger.el;

public class PermanentEvaluationException extends EvaluationException {
  public PermanentEvaluationException(String message, String expr) {
    super(message, expr);
  }

  public PermanentEvaluationException(String message, String expr, Throwable cause) {
    super(message, expr, cause);
  }
}
