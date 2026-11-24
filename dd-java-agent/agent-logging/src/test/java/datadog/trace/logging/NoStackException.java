package datadog.trace.logging;

/** Exception without stack trace, so that logged output is deterministic. */
public class NoStackException extends Exception {
  public NoStackException(String message) {
    super(message, null, false, false);
  }
}
