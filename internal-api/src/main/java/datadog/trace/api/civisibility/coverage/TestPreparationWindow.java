package datadog.trace.api.civisibility.coverage;

import javax.annotation.Nullable;

/**
 * Coverage of the code that a test framework runs for a test before it reports the test started,
 * such as JUnit 5 building the test instance. A framework integration opens the window where it
 * starts preparing a test, on the thread that then runs the test, and the test's window takes it
 * over when the test starts. Probes that no test takes, as when the test is skipped before it
 * starts, go to Shared.
 */
public final class TestPreparationWindow extends CoverageWindow {

  private static final ThreadLocal<TestPreparationWindow> OPEN = new ThreadLocal<>();

  private static volatile boolean enabled;

  private TestPreparationWindow() {}

  /** Called once line probes are inserted; until then {@link #open} does nothing. */
  public static void enable() {
    enabled = true;
  }

  /** Opens a window on this thread, first giving any window still open on it to Shared. */
  public static void open() {
    if (!enabled) {
      return;
    }
    discard();
    OPEN.set(new TestPreparationWindow());
  }

  /**
   * Removes the window open on this thread, if any, so that the window of the test that starts can
   * take it over.
   */
  @Nullable
  public static CoverageWindow detach() {
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
}
