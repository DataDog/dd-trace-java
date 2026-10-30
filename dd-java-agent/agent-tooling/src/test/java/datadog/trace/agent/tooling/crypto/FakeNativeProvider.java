package datadog.trace.agent.tooling.crypto;

import java.security.Provider;

/** Stands in for ACCP: same {@code INSTANCE} field and health-check methods. */
public final class FakeNativeProvider extends Provider {
  static final String NAME = "FakeNativeProvider";

  static volatile Throwable loadingError;
  static volatile boolean fips;
  static volatile boolean healthy = true;

  public static final FakeNativeProvider INSTANCE = new FakeNativeProvider();

  @SuppressWarnings("deprecation")
  private FakeNativeProvider() {
    super(NAME, 1.0, "fake native provider for tests");
  }

  static void reset() {
    loadingError = null;
    fips = false;
    healthy = true;
  }

  public Throwable getLoadingError() {
    return loadingError;
  }

  public boolean isFips() {
    return fips;
  }

  public void assertHealthy() {
    if (!healthy) {
      throw new IllegalStateException("self-test failed");
    }
  }
}
