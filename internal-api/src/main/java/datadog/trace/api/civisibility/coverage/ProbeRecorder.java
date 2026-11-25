package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Receives hits from the line probes that {@code ProbeClassTransformer} inserts into application
 * classes.
 *
 * <p>Hits are kept in probe tables, one per owner of the running code: an open {@link
 * CoverageWindow}, or Shared for code that no open window owns. A table holds one probe array per
 * instrumented class. An instrumented method takes the array of its owner once, at entry, from
 * {@link #probes}. A probe reads its slot and calls {@link #record} only when the slot is not set,
 * so after the first hit it costs one array read. {@link #record} sets the slot in the table of the
 * code's owner. Slot 0 of every array marks the class as touched.
 *
 * <p>Code belongs to the window opened on the thread that runs it, else to the window of the test
 * whose context the thread carries, else to Shared. The first window to open while no other is
 * open, the primary window, uses the table {@link #PROBES}, and its thread takes arrays without
 * looking up its owner. Windows that open while the primary window is open take pooled tables of
 * their own. Shared is never reset: it is reported once per process.
 *
 * <p>A method that took the array of a window that has since closed reads slots that the window
 * reset, and that a later window may have set; it can skip such probes until it returns. Lines lost
 * this way were executed after their window closed, so they belong to Shared, not to a test.
 *
 * <p>Class initializers read {@link #PROBES} directly: their slots are never reset and need no
 * owner.
 */
public final class ProbeRecorder {

  /** Upper bound on instrumented classes per process; classes beyond it are not instrumented. */
  public static final int MAX_CLASSES = 1 << 16;

  /** Probe arrays of the primary window and of class initializers, indexed by class id. */
  public static final boolean[][] PROBES = new boolean[MAX_CLASSES][];

  static final InstrumentedClass[] CLASSES = new InstrumentedClass[MAX_CLASSES];

  private static final AtomicInteger NEXT_CLASS_ID = new AtomicInteger();

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

  /** Called by instrumented code at method entry: the probe array of the code's owner. */
  public static boolean[] probes(int classId) {
    if (Thread.currentThread() == primaryThread) {
      return PROBES[classId];
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
      // instrumented code must never see an exception from the recorder
      return PROBES[classId];
    }
  }

  /** Called by instrumented code on the first hit of a probe in the array it took at entry. */
  public static void record(int classId, int probeId) {
    try {
      Thread current = Thread.currentThread();
      CoverageWindow window = current == primaryThread ? primaryWindow : null;
      if (window == null || window.thread() != current) {
        window = ownerWindow();
      }
      if (window == null || !window.mark(classId, probeId)) {
        boolean[] probes = sharedProbes(classId);
        probes[probeId] = true;
        probes[0] = true;
      }
    } catch (Throwable ignored) {
      // instrumented code must never see an exception from the recorder
    }
  }

  /** Called on every execution by probes that cannot branch; records only a first hit. */
  public static void hit(boolean[] probes, int classId, int probeId) {
    try {
      if (!probes[probeId]) {
        record(classId, probeId);
      }
    } catch (Throwable ignored) {
      // instrumented code must never see an exception from the recorder
    }
  }

  /** Called by probes in class initializers: their slots are never reset and need no owner. */
  public static void recordInit(int classId, int probeId) {
    try {
      PROBES[classId][probeId] = true;
    } catch (Throwable ignored) {
      // instrumented code must never see an exception from the recorder
    }
  }

  /**
   * Called when an exception leaves or is caught in a method, with the probe of the last code the
   * method started that could throw; 0 if it started none.
   */
  public static void recordThrown(int classId, int probeId) {
    if (probeId != 0) {
      record(classId, probeId);
    }
  }

  /** {@link #recordThrown} for class initializers. */
  public static void recordInitThrown(int classId, int probeId) {
    if (probeId != 0) {
      recordInit(classId, probeId);
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
    if (probes == null || !probes[0]) {
      return false;
    }
    CLASSES[classId].addMethodLines(probes, lines);
    return true;
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
    return probes != null ? probes : allocate(SHARED, classId);
  }

  /** Allocates the probe array of a class in a table other than {@link #PROBES}. */
  static boolean[] allocate(boolean[][] table, int classId) {
    synchronized (table) {
      boolean[] probes = table[classId];
      if (probes == null) {
        probes = new boolean[CLASSES[classId].probeCount()];
        table[classId] = probes;
      }
      return probes;
    }
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
        return PROBES;
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
   * Takes back the table of a closed window, after the window reset every slot it set.
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
