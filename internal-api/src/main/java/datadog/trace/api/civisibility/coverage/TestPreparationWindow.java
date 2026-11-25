package datadog.trace.api.civisibility.coverage;

import java.util.Collections;
import java.util.List;

/**
 * Coverage of the code that a test framework runs for a test before it reports the test started,
 * such as JUnit 5 building the test instance. A framework integration opens the window where it
 * starts preparing a test, on the thread that then runs the test, and the test takes the window's
 * probes when it starts. Probes that no test takes, as when the test is skipped before it starts,
 * go to Shared.
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
   * Closes the window open on this thread, if any.
   *
   * @return the probes the window hit
   */
  public static List<ClassHits> take() {
    TestPreparationWindow window = OPEN.get();
    if (window == null) {
      return Collections.emptyList();
    }
    OPEN.remove();
    return window.close();
  }

  /** Gives the probes of the window open on this thread, if any, to Shared. */
  public static void discard() {
    ProbeRecorder.addSharedHits(take());
  }
}
