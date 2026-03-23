package datadog.trace.instrumentation.gson;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;

public class GsonHelper {
  // Use a stable class reference that won't cause muzzle failures
  // We use the helper class itself as the key
  private static final Class<?> CALL_DEPTH_KEY = GsonHelper.class;

  public static int incrementCallDepth() {
    return CallDepthThreadLocalMap.incrementCallDepth(CALL_DEPTH_KEY);
  }

  public static void resetCallDepth() {
    CallDepthThreadLocalMap.reset(CALL_DEPTH_KEY);
  }
}
