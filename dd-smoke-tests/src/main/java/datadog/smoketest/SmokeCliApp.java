package datadog.smoketest;

import java.util.concurrent.TimeUnit;

/**
 * A batch/CLI smoke app that runs to completion (rather than staying up as a server). On start-up
 * it only fails fast if the process has already exited abnormally; there is no port to wait for and
 * no per-method backend reset (a batch app may have produced all its traces at start-up, so
 * clearing between methods would discard them).
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SmokeCliApp app = SmokeCliApp.named("opentelemetry")
 *     .jar(System.getProperty("datadog.smoketest.shadowJar.path"))
 *     .backend(TraceBackend.testAgent())
 *     .build();
 *
 * @Test
 * void runsToCompletion() {
 *   app.traces().waitForTraceCount(11, 30);
 *   app.assertCompletesWithValue(30, SECONDS, 0);
 * }
 * }</pre>
 */
public final class SmokeCliApp extends AbstractSmokeApp {

  private SmokeCliApp(Builder builder) {
    super(builder);
  }

  /** Starts a fluent builder for a batch/CLI app with the given (log/diagnostic) name. */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /**
   * Asserts the app runs to completion within the timeout and exits with {@code expectedExitValue}.
   * Fails with an {@link AssertionError} if it doesn't terminate in time or exits with a different
   * code. Pass a non-zero value for apps expected to fail (e.g. a tool the agent aborts).
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

  @Override
  protected void onStarted() {
    // A batch app may already have run to completion at start-up; only fail fast if it exited
    // abnormally. (Server-style port waiting doesn't apply.)
    if (!process().isAlive() && process().exitValue() != 0) {
      throw new IllegalStateException(
          "App '" + name() + "' exited abnormally on start (exit " + process().exitValue() + ")");
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
