package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.api.Platform;

public final class InstrumentationBasedProfiling {
  private static volatile boolean isJFRReady;

  public static void enableInstrumentationBasedProfiling() {
    isJFRReady = true;
  }

  public static boolean isJFRReady() {
    return isJFRReady && !Platform.isNativeImageBuilder();
  }
}
