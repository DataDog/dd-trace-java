package datadog.trace.instrumentation.scalatest.execution;

import org.scalatest.exceptions.TestCanceledException;

public class SuppressedTestFailedException extends TestCanceledException {
  public SuppressedTestFailedException(String message, Throwable cause, int failedCodeStackDepth) {
    super(message, cause, failedCodeStackDepth);
  }
}
