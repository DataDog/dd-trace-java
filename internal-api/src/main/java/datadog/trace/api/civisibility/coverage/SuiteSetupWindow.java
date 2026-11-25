package datadog.trace.api.civisibility.coverage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Coverage of a suite's setup, such as the {@code @BeforeAll} methods of a JUnit 5 class: what the
 * thread that starts the suite runs until the suite hands work to its first child. Every test in
 * the suite, nested suites included, is credited with these lines, and so is Shared. What runs
 * after that, such as the suite's teardown, is not setup.
 *
 * <p>A framework integration calls {@link #open} when a suite starts, {@link #childStarts} on the
 * same thread when the suite hands work to a child, and {@link #finish} when the suite finished.
 * Setup windows do not nest: a suite starts only after its parent's setup ended.
 */
public final class SuiteSetupWindow extends CoverageWindow {

  private static final ThreadLocal<SuiteSetupWindow> OPEN = new ThreadLocal<>();

  /** The setup hits that the tests of each running suite are credited with, by suite. */
  private static final Map<Object, Hits> HITS = new ConcurrentHashMap<>();

  static volatile boolean enabled;

  private final Object suite;
  @Nullable private final Hits outerHits;

  private SuiteSetupWindow(Object suite, @Nullable Hits outerHits) {
    this.suite = suite;
    this.outerHits = outerHits;
  }

  /**
   * A suite starts on this thread.
   *
   * @param suite the framework's object for the suite, which later calls pass again
   * @param parent the suite that contains it, if any
   * @param hasSetup whether the suite runs setup that its tests depend on; the tests of a suite
   *     without it are still credited with the setup of the suites that contain it
   */
  public static void open(Object suite, @Nullable Object parent, boolean hasSetup) {
    if (!enabled) {
      return;
    }
    Hits outerHits = parent != null ? HITS.get(parent) : null;
    // a suite that starts inside another window, such as a test that runs a test engine, leaves
    // its lines to that window
    if (hasSetup && OPEN.get() == null && !ProbeRecorder.threadProbes().hasOpenWindow()) {
      OPEN.set(new SuiteSetupWindow(suite, outerHits));
    } else if (outerHits != null) {
      HITS.put(suite, outerHits);
    }
  }

  /** The suite hands work to a child on this thread: its setup, if still open, ends. */
  public static void childStarts(Object suite) {
    SuiteSetupWindow window = OPEN.get();
    if (window == null || window.suite != suite) {
      return;
    }
    OPEN.remove();
    List<ClassHits> hits = window.close();
    ProbeRecorder.addSharedHits(hits);
    if (!hits.isEmpty()) {
      HITS.put(suite, new Hits(hits, window.outerHits));
    } else if (window.outerHits != null) {
      HITS.put(suite, window.outerHits);
    }
  }

  /** The suite finished: its setup ends if no child started, and its hits are dropped. */
  public static void finish(Object suite) {
    SuiteSetupWindow window = OPEN.get();
    if (window != null && window.suite == suite) {
      OPEN.remove();
      // no test ran after this setup
      ProbeRecorder.addSharedHits(window.close());
    }
    HITS.remove(suite);
  }

  /** The setup hits that a test in {@code suite} is credited with, or null if there are none. */
  @Nullable
  static Hits hitsOf(@Nullable Object suite) {
    return suite != null ? HITS.get(suite) : null;
  }

  /** What the setup of a suite hit, linked to what the setup of the suites around it hit. */
  public static final class Hits {
    public final List<ClassHits> hits;
    @Nullable public final Hits outer;

    Hits(List<ClassHits> hits, @Nullable Hits outer) {
      this.hits = hits;
      this.outer = outer;
    }
  }
}
