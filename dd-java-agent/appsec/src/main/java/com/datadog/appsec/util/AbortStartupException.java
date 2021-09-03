package com.datadog.appsec.util;

public class AbortStartupException extends RuntimeException {
  public AbortStartupException(String message, Throwable cause) {
    super(message, cause);
  }

  public AbortStartupException(Throwable cause) {
    super(cause);
  }
}
