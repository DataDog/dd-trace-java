package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Gives the line probes that {@code ProbeClassTransformer} inserts into application classes the
 * arrays they store their hits in.
 *
 * <p>Hits are kept in probe tables, one per owner of the running code: an open {@link
 * CoverageWindow}, or Shared for code that no open window owns. A table holds one probe array per
 * instrumented class. An instrumented method takes the array of its owner once, at entry, from
 * {@link #probes}, and each probe stores {@code true} in its slot of that array.
 *
 * <p>Code belongs to the window opened on the thread that runs it, else to the window of the test
 * whose context the thread carries, else to Shared. The first window to open while no other is
 * open, the primary window, uses the table {@link #PRIMARY}, and its thread takes arrays without
 * looking up its owner. Windows that open while the primary window is open take pooled tables of
 * their own. A window allocates the array of a class when code of the class first takes it, and
 * drops its arrays when it closes, so a later window that uses the same table gets new ones. Shared
 * is never reset: it is reported once per process.
 *
 * <p>A method that took the array of a window that has since closed stores into an array that no
 * window reads: the lines it runs from then on are lost.
 *
 * <p>Class initializers store into {@link #PROBES} directly: their slots are never reset and need
 * no owner.
 */
public final class ProbeRecorder {

  /** Upper bound on instrumented classes per process; classes beyond it are not instrumented. */
  public static final int MAX_CLASSES = 1 << 16;

  /** Probe arrays of class initializers, indexed by class id. */
  public static final boolean[][] PROBES = new boolean[MAX_CLASSES][];

  static final InstrumentedClass[] CLASSES = new InstrumentedClass[MAX_CLASSES];

  private static final AtomicInteger NEXT_CLASS_ID = new AtomicInteger();

  /** Probe arrays of the primary window, indexed by class id. */
  private static final boolean[][] PRIMARY = new boolean[MAX_CLASSES][];

  /** Probe arrays of code that no open window owned, allocated on first use. */
  private static final boolean[][] SHARED = new boolean[MAX_CLASSES][];

  private static final ThreadLocal<CoverageWindow> OWN_WINDOW = new ThreadLocal<>();

  private static final Object WINDOWS_LOCK = new Object();

  // guarded by WINDOWS_LOCK
  private static CoverageWindow primaryWindow;
  private static int windowsNestedInPrimary;
  private static final ArrayDeque<boolean[][]> FREE_TABLES = new ArrayDeque<>();

  /**
   * The thread of the primary window, or null when no window is primary or another window is open
   * on the primary window's thread. Written under {@link #WINDOWS_LOCK} and read without it: a
   * thread can see itself here only after it opened the primary window itself.
   */
  private static Thread primaryThread;

  private ProbeRecorder() {}

  /**
   * @return a new class id, or -1 if the limit is reached
   */
  public static int reserveClassId() {
    int id = NEXT_CLASS_ID.getAndIncrement();
    return id < MAX_CLASSES ? id : -1;
  }

  /** Allocates the probe array of a class. Must be called before the class is defined. */
  public static void register(int classId, InstrumentedClass instrumentedClass) {
    CLASSES[classId] = instrumentedClass;
    PROBES[classId] = new boolean[instrumentedClass.probeCount()];
  }

  public static InstrumentedClass getInstrumentedClass(int classId) {
    return CLASSES[classId];
  }

  public static int getClassCount() {
    return Math.min(NEXT_CLASS_ID.get(), MAX_CLASSES);
  }

  /**
   * Called by instrumented code at method entry: the probe array of the code's owner. Slot 0 is
   * stored into by exception handlers that ran before any probe and is never read.
   */
  public static boolean[] probes(int classId) {
    if (Thread.currentThread() == primaryThread) {
      boolean[] probes = PRIMARY[classId];
      if (probes != null) {
        return probes;
      }
    }
    return ownerProbes(classId);
  }

  private static boolean[] ownerProbes(int classId) {
    try {
      CoverageWindow window = ownerWindow();
      if (window != null) {
        boolean[] probes = window.probes(classId);
        if (probes != null) {
          return probes;
        }
      }
      return sharedProbes(classId);
    } catch (Throwable e) {
      // instrumented code must never see an exception from the recorder; nothing reads the method
      // probes of this array
      return PROBES[classId];
    }
  }

  /** Gives to Shared the probes that a closed window hit and that no test is credited with. */
  public static void addSharedHits(List<CoverageWindow.ClassHits> hits) {
    for (CoverageWindow.ClassHits classHits : hits) {
      boolean[] probes = sharedProbes(classHits.classId);
      long[] bits = classHits.probes;
      for (int word = 0; word < bits.length; word++) {
        for (long remaining = bits[word]; remaining != 0; remaining &= remaining - 1) {
          probes[(word << 6) + Long.numberOfTrailingZeros(remaining)] = true;
        }
      }
    }
  }

  /**
   * Adds to {@code lines} the lines of a class that ran while no open window owned them, other than
   * its initializer lines.
   *
   * @return {@code false} if no such line ran
   */
  public static boolean addSharedLines(int classId, BitSet lines) {
    boolean[] probes = SHARED[classId];
    return probes != null && CLASSES[classId].addMethodLines(probes, lines);
  }

  /**
   * The window that owns code running on this thread: the open window opened on this thread, else
   * the window of the test whose context the thread carries.
   */
  @Nullable
  private static CoverageWindow ownerWindow() {
    CoverageWindow own = OWN_WINDOW.get();
    if (own != null && !own.isClosed()) {
      return own;
    }
    TestContext test = InstrumentationTestBridge.getCurrentTestContext();
    if (test != null) {
      CoverageStore store = test.getCoverageStore();
      if (store instanceof CoverageWindow) {
        return (CoverageWindow) store;
      }
    }
    return null;
  }

  private static boolean[] sharedProbes(int classId) {
    boolean[] probes = SHARED[classId];
    if (probes != null) {
      return probes;
    }
    synchronized (SHARED) {
      probes = SHARED[classId];
      if (probes == null) {
        probes = newProbes(classId);
        SHARED[classId] = probes;
      }
      return probes;
    }
  }

  static boolean[] newProbes(int classId) {
    return new boolean[CLASSES[classId].probeCount()];
  }

  /** The window opened on this thread, if any. */
  @Nullable
  static CoverageWindow ownWindow() {
    return OWN_WINDOW.get();
  }

  /** Opens a window on the current thread, which must be the window's thread. */
  static boolean[][] openWindow(CoverageWindow window) {
    OWN_WINDOW.set(window);
    synchronized (WINDOWS_LOCK) {
      if (primaryWindow == null) {
        primaryWindow = window;
        primaryThread = window.thread();
        return PRIMARY;
      }
      if (primaryWindow.thread() == window.thread()) {
        // the primary window's thread now runs code of this window
        window.suspendedPrimary = primaryWindow;
        windowsNestedInPrimary++;
        primaryThread = null;
      }
      boolean[][] table = FREE_TABLES.poll();
      return table != null ? table : new boolean[MAX_CLASSES][];
    }
  }

  /**
   * Takes back the table of a closed window, after the window dropped every array it allocated.
   *
   * @param outer the window that was open on the window's thread when it opened
   */
  static void closeWindow(
      CoverageWindow window, boolean[][] table, @Nullable CoverageWindow outer) {
    synchronized (WINDOWS_LOCK) {
      if (window == primaryWindow) {
        primaryWindow = null;
        primaryThread = null;
        windowsNestedInPrimary = 0;
      } else {
        FREE_TABLES.push(table);
        if (window.suspendedPrimary != null
            && window.suspendedPrimary == primaryWindow
            && --windowsNestedInPrimary == 0) {
          primaryThread = primaryWindow.thread();
        }
      }
    }
    if (OWN_WINDOW.get() == window) {
      OWN_WINDOW.set(outer != null && !outer.isClosed() ? outer : null);
    }
  }
}
