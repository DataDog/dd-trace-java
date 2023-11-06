package com.datadog.debugger.el;

public class RedactedException extends EvaluationException {
  public RedactedException(String message, String expr) {
    super(message, expr);
  }
}
