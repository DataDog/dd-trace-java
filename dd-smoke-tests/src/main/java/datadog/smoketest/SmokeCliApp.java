package datadog.smoketest;

import java.util.concurrent.TimeUnit;

/**
 * A batch/CLI smoke app that runs to completion (rather than staying up as a server). It has no
 * readiness check on start-up as there is no port to wait for, its exit is asserted from the test
 * body with {@link #assertCompletesWithValue}, and no per-method backend reset (a batch app may
 * have produced all its traces at start-up, so clearing between methods would discard them).
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SmokeCliApp app = SmokeCliApp.named("my-application")
 *     .jar(System.getProperty("datadog.smoketest.shadowJar.path"))
 *     .backend(AgentBackend.testAgent())
 *     .build();
 *
 * @Test
 * void runsToCompletion() {
 *   app.traces().assertTraces(...);
 *   app.assertCompletesWithValue(30, SECONDS, 0);
 * }
 * }</pre>
 */
public final class SmokeCliApp extends AbstractSmokeApp {

  private SmokeCliApp(Builder builder) {
    super(builder);
  }

  /**
   * Starts a fluent builder for a batch/CLI app.
   *
   * @param name The application (log/diagnostic) name.
   * @return A new builder for a {@link SmokeCliApp}.
   */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /**
   * Asserts the app runs to completion within the timeout and exits with the expected value. Pass a
   * non-zero expected value for apps expected to fail (e.g. a tool the agent aborts).
   *
   * @param timeout The maximum time to wait for the app to complete.
   * @param unit The time unit of {@code timeout}.
   * @param expectedExitValue The exit code the app is expected to return.
   * @throws AssertionError If the app does not terminate in time or exits with a different code.
   */
  public void assertCompletesWithValue(long timeout, TimeUnit unit, int expectedExitValue) {
    boolean exited;
    try {
      exited = process().waitFor(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for app '" + name() + "' to complete", e);
    }
    if (!exited) {
      throw new AssertionError(
          "App '" + name() + "' did not complete within " + timeout + " " + unit);
    }
    int actual = process().exitValue();
    if (actual != expectedExitValue) {
      throw new AssertionError(
          "App '" + name() + "' exited with " + actual + " but expected " + expectedExitValue);
    }
  }

  /** Fluent builder for a {@link SmokeCliApp}; obtain via {@link SmokeCliApp#named(String)}. */
  public static final class Builder extends AbstractSmokeApp.Builder<SmokeCliApp, Builder> {
    private Builder(String name) {
      super(name);
    }

    @Override
    protected Builder self() {
      return this;
    }

    @Override
    public SmokeCliApp build() {
      validate();
      return new SmokeCliApp(this);
    }
  }
}
