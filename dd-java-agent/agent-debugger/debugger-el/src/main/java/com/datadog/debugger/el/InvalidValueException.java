package com.datadog.debugger.el;

public class InvalidValueException extends RuntimeException {
  public InvalidValueException(String message) {
    super(message);
  }

  public InvalidValueException(String message, Throwable cause) {
    super(message, cause);
  }

  public InvalidValueException(Throwable cause) {
    super(cause);
  }

  public InvalidValueException(
      String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
