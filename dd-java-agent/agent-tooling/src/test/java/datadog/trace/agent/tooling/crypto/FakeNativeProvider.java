package datadog.trace.agent.tooling.crypto;

import java.security.Provider;

public final class FakeNativeProvider extends Provider {
  static final String NAME = "FakeNativeProvider";

  static volatile Throwable loadingError;
  static volatile boolean fips;
  static volatile boolean healthy = true;
  static volatile Runnable beforeHealthCheck;

  public static final FakeNativeProvider INSTANCE = new FakeNativeProvider();

  @SuppressWarnings("deprecation")
  private FakeNativeProvider() {
    super(NAME, 1.0, "fake native provider for tests");
  }

  static void reset() {
    loadingError = null;
    fips = false;
    healthy = true;
    beforeHealthCheck = null;
  }

  public Throwable getLoadingError() {
    return loadingError;
  }

  public boolean isFips() {
    return fips;
  }

  public void assertHealthy() {
    Runnable hook = beforeHealthCheck;
    if (hook != null) {
      hook.run();
    }
    if (!healthy) {
      throw new IllegalStateException("self-test failed");
    }
  }
}
