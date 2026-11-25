package datadog.trace.api.civisibility.coverage;

import javax.annotation.Nullable;

/**
 * Coverage of the code that a test framework runs for a test before it reports the test started,
 * such as JUnit 5 building the test instance. A framework integration opens the window where it
 * starts preparing a test, on the thread that then runs the test, and the test's window takes it
 * over when the test starts. Probes that no test takes, as when the test is skipped before it
 * starts, go to Shared.
 *
 * <p>The window also carries the setup hits of the suites the test runs in (see {@link
 * SuiteSetupWindow}), so that a test skipped before it starts is not credited with them either.
 */
public final class TestPreparationWindow extends CoverageWindow {

  private static final ThreadLocal<TestPreparationWindow> OPEN = new ThreadLocal<>();

  private static volatile boolean enabled;

  @Nullable private final SuiteSetupWindow.Hits suiteSetupHits;

  private TestPreparationWindow(@Nullable SuiteSetupWindow.Hits suiteSetupHits) {
    this.suiteSetupHits = suiteSetupHits;
  }

  /**
   * Called once line probes are inserted; until then {@link #open} and {@link
   * SuiteSetupWindow#open} do nothing.
   */
  public static void enable() {
    enabled = true;
    SuiteSetupWindow.enabled = true;
  }

  /**
   * Opens a window on this thread, first giving any window still open on it to Shared.
   *
   * @param suite the framework's object for the suite the test runs in, as passed to {@link
   *     SuiteSetupWindow#open}, or null
   */
  public static void open(@Nullable Object suite) {
    if (!enabled) {
      return;
    }
    discard();
    OPEN.set(new TestPreparationWindow(SuiteSetupWindow.hitsOf(suite)));
  }

  /**
   * Removes the window open on this thread, if any, so that the window of the test that starts can
   * take it over.
   */
  @Nullable
  public static TestPreparationWindow detach() {
    TestPreparationWindow window = OPEN.get();
    if (window != null) {
      OPEN.remove();
    }
    return window;
  }

  /** Gives the probes of the window open on this thread, if any, to Shared. */
  public static void discard() {
    CoverageWindow window = detach();
    if (window != null) {
      ProbeRecorder.addSharedHits(window.close());
    }
  }

  /** The setup hits of the suites the test runs in, or null if there are none. */
  @Nullable
  public SuiteSetupWindow.Hits suiteSetupHits() {
    return suiteSetupHits;
  }
}
