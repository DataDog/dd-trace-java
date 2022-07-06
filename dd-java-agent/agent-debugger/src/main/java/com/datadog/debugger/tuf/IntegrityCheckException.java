package com.datadog.debugger.tuf;

/** Exception when checking configuration integrity */
public class IntegrityCheckException extends RuntimeException {
  public IntegrityCheckException(String message) {
    super(message);
  }

  public IntegrityCheckException(String message, Throwable cause) {
    super(message, cause);
  }
}
