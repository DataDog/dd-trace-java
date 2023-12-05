package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.api.Platform;

public final class InstrumentationBasedProfiling {
  private static volatile boolean isJFRReady;

  public static void enableInstrumentationBasedProfiling() {
    // ignore the enablement when running in native image builder
    if (!Platform.isNativeImageBuilder()) {
      isJFRReady = true;
    }
  }

  public static boolean isJFRReady() {
    return isJFRReady;
  }
}
