package datadog.trace.agent.tooling.crypto;

import java.security.Provider;

/** A provider without an {@code INSTANCE} field, created through its no-arg constructor. */
public final class ConstructedProvider extends Provider {
  static final String NAME = "ConstructedProvider";

  @SuppressWarnings("deprecation")
  public ConstructedProvider() {
    super(NAME, 1.0, "constructed provider for tests");
  }

  public Throwable getLoadingError() {
    return null;
  }

  public boolean isFips() {
    return false;
  }

  public void assertHealthy() {}
}
