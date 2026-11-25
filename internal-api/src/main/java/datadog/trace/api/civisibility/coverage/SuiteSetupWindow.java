package datadog.trace.api.civisibility.coverage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Coverage of a suite's setup, such as the {@code @BeforeAll} methods of a JUnit 5 class: what the
 * thread that starts the suite runs until the suite's first child starts. That includes the
 * preparation of the first child, such as building the first test instance, where a test can build
 * shared state lazily that later tests reuse. Every test in the suite, nested suites included, is
 * credited with these lines, and so is Shared. What runs after that, such as the suite's teardown,
 * is not setup.
 *
 * <p>A framework integration calls {@link #open} when a suite starts, {@link #handOver} on the same
 * thread when the suite hands its context to a child, {@link #coversPreparation} where it starts
 * preparing a test, {@link #childStarts} where a test or a suite starts, and {@link #finish} when
 * the suite finished.
 */
public final class SuiteSetupWindow extends CoverageWindow {

  private static final ThreadLocal<SuiteSetupWindow> OPEN = new ThreadLocal<>();

  /** The setup hits that the tests of each running suite are credited with, by suite. */
  private static final Map<Object, Hits> HITS = new ConcurrentHashMap<>();

  static volatile boolean enabled;

  private final Object suite;
  @Nullable private final Hits outerHits;

  /** Whether the suite handed its context to a child, so that the window covers its preparation. */
  private final boolean childPreparation;

  private SuiteSetupWindow(Object suite, @Nullable Hits outerHits, boolean childPreparation) {
    this.suite = suite;
    this.outerHits = outerHits;
    this.childPreparation = childPreparation;
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
      OPEN.set(new SuiteSetupWindow(suite, outerHits, false));
    } else if (outerHits != null) {
      HITS.put(suite, outerHits);
    }
  }

  /**
   * The suite hands its context to a child on this thread. Its tests are credited with what its
   * setup hit so far, which children that run on other threads need from here on; the window stays
   * open on this thread until a child starts.
   */
  public static void handOver(Object suite) {
    SuiteSetupWindow window = OPEN.get();
    if (window == null || window.suite != suite || window.childPreparation) {
      return;
    }
    OPEN.set(new SuiteSetupWindow(suite, window.end(), true));
  }

  /**
   * A test of {@code suite} starts being prepared on this thread.
   *
   * @return whether the preparation is part of the suite's setup; if not, the caller opens the
   *     test's own preparation window
   */
  public static boolean coversPreparation(@Nullable Object suite) {
    SuiteSetupWindow window = OPEN.get();
    if (window == null || !window.childPreparation) {
      return false;
    }
    if (window.suite == suite) {
      return true;
    }
    // a child of another suite runs here first, as with parallel execution: that setup ended
    OPEN.remove();
    window.end();
    return false;
  }

  /**
   * A test or a suite whose parent is {@code suite} starts on this thread. A setup window that
   * covered a child's preparation ends.
   *
   * @return whether the setup of {@code suite} ended: the test that starts was prepared within it,
   *     so the caller opens the window that the test's window takes over
   */
  public static boolean childStarts(@Nullable Object suite) {
    SuiteSetupWindow window = OPEN.get();
    if (window == null || !window.childPreparation) {
      return false;
    }
    OPEN.remove();
    window.end();
    return window.suite == suite;
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

  /**
   * Closes the window and gives its hits to Shared and to the tests of the suite.
   *
   * @return the setup hits of the suite's tests from now on
   */
  @Nullable
  private Hits end() {
    List<ClassHits> hits = close();
    ProbeRecorder.addSharedHits(hits);
    Hits suiteHits = hits.isEmpty() ? outerHits : new Hits(hits, outerHits);
    if (suiteHits != null) {
      HITS.put(suite, suiteHits);
    }
    return suiteHits;
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
