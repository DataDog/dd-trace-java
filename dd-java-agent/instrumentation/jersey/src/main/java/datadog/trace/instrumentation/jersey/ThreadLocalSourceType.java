package datadog.trace.instrumentation.jersey;

import datadog.trace.api.iast.SourceTypes;

public class ThreadLocalSourceType {
  private static final ThreadLocal<Byte> SOURCE =
      ThreadLocal.withInitial(() -> SourceTypes.REQUEST_PARAMETER_VALUE);

  public static void set(byte source) {
    SOURCE.set(source);
  }

  public static byte get() {
    return SOURCE.get();
  }
}
