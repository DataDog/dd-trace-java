package datadog.trace.bootstrap.instrumentation.api;

public class ErrorPriorities {
  public static final byte UNSET = Byte.MIN_VALUE;
  public static final byte HTTP_SERVER_DECORATOR = -1;

  public static final byte DEFAULT = 0;
}
