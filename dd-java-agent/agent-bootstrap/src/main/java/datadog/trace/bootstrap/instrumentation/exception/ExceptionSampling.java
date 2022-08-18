package datadog.trace.bootstrap.instrumentation.exceptions;

public final class ExceptionSampling {
  private static volatile boolean canSampleExceptions;

  public static void enableExceptionSampling() {
    canSampleExceptions = true;
  }

  public static boolean canSampleExceptions() {
    return canSampleExceptions;
  }
}
