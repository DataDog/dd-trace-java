package datadog.trace.civisibility.config;

import java.util.Objects;

public class TestManagementSettings {

  public static final TestManagementSettings DEFAULT = new TestManagementSettings(false, -1);

  private final boolean enabled;
  private final int attemptToFixRetries;

  public TestManagementSettings(boolean enabled, int attemptToFixRetries) {
    this.enabled = enabled;
    this.attemptToFixRetries = attemptToFixRetries;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getAttemptToFixRetries() {
    return attemptToFixRetries;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    TestManagementSettings that = (TestManagementSettings) o;
    return enabled == that.enabled && attemptToFixRetries == that.attemptToFixRetries;
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, attemptToFixRetries);
  }
}
