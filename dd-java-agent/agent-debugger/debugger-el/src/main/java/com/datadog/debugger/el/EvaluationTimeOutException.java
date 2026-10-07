package com.datadog.debugger.el;

public class EvaluationTimeOutException extends EvaluationException {
  public EvaluationTimeOutException(String message, String expr) {
    super(message, expr);
  }
}
