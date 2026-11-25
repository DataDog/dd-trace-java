package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Gives the line probes that {@code ProbeClassTransformer} inserts into application classes the
 * arrays they store their hits in.
 *
 * <p>Each thread that runs instrumented code has probe arrays of its own, one per class, and only
 * the thread stores into them. An instrumented method takes its thread's array once, at entry, from
 * {@link #probes}, and each probe stores {@code true} in its slot of that array. What an array
 * holds belongs to the owner of the code the thread ran when the array was handed out: an open
 * {@link CoverageWindow}, or Shared for code that no open window owns. {@link ThreadProbes}
 * describes how the hits reach their owner. Shared is never reset: it is reported once per process.
 *
 * <p>The first thread that opens a window is the primary thread: while a window is open on it, it
 * takes its arrays from {@link #PRIMARY} without looking up its owner.
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

  /** Probe arrays of the primary thread, indexed by class id. */
  private static final boolean[][] PRIMARY = new boolean[MAX_CLASSES][];

  /** Probes hit by code that no open window owned, allocated on first use. */
  private static final boolean[][] SHARED = new boolean[MAX_CLASSES][];

  private static final ThreadLocal<ThreadProbes> THREAD_PROBES =
      new ThreadLocal<ThreadProbes>() {
        @Override
        protected ThreadProbes initialValue() {
          return register(new ThreadProbes(Thread.currentThread()));
        }
      };

  private static final Object LOCK = new Object();

  // guarded by LOCK
  private static ThreadProbes primary;
  private static ThreadProbes[] registered = new ThreadProbes[64];
  private static int registeredCount;

  /**
   * The primary thread while a window is open on it, else null. Read without a lock: a thread can
   * see itself here only after it wrote itself here.
   */
  static Thread primaryThread;

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
   * Called by instrumented code at method entry: the current thread's probe array of a class. Slot
   * 0 is stored into by exception handlers that ran before any probe and is never read.
   */
  public static boolean[] probes(int classId) {
    if (Thread.currentThread() == primaryThread) {
      boolean[] probes = PRIMARY[classId];
      if (probes != null) {
        return probes;
      }
    }
    return threadProbes(classId);
  }

  private static boolean[] threadProbes(int classId) {
    try {
      return THREAD_PROBES.get().take(classId);
    } catch (Throwable e) {
      // instrumented code must never see an exception from the recorder; nothing reads the method
      // probes of this array
      return PROBES[classId];
    }
  }

  /**
   * Moves to Shared what threads hit while no open window owned their code. Call it before reading
   * Shared with {@link #addSharedLines}.
   */
  public static void flushShared() {
    ThreadProbes[] all;
    synchronized (LOCK) {
      all = Arrays.copyOf(registered, registeredCount);
    }
    for (ThreadProbes threadProbes : all) {
      threadProbes.flushShared();
    }
  }

  /** Gives to Shared the probes that a window hit and that no test is credited with. */
  public static void addSharedHits(List<CoverageWindow.ClassHits> hits) {
    for (CoverageWindow.ClassHits classHits : hits) {
      addSharedHits(classHits.classId, classHits.probes);
    }
  }

  static void addSharedHits(int classId, long[] hits) {
    boolean[] probes = sharedProbes(classId);
    for (int word = 0; word < hits.length; word++) {
      for (long remaining = hits[word]; remaining != 0; remaining &= remaining - 1) {
        probes[(word << 6) + Long.numberOfTrailingZeros(remaining)] = true;
      }
    }
  }

  /**
   * Adds to {@code lines} the lines of a class that ran while no open window owned them, other than
   * its initializer lines. Only hits moved to Shared are seen: see {@link #flushShared}.
   *
   * @return {@code false} if no such line ran
   */
  public static boolean addSharedLines(int classId, BitSet lines) {
    boolean[] probes = SHARED[classId];
    return probes != null && CLASSES[classId].addMethodLines(probes, lines);
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

  /** The probe arrays of the current thread. */
  static ThreadProbes threadProbes() {
    return THREAD_PROBES.get();
  }

  /**
   * The window of the test whose context the current thread carries, if the test has one and it is
   * open.
   */
  @Nullable
  static CoverageWindow contextWindow() {
    TestContext test = InstrumentationTestBridge.getCurrentTestContext();
    if (test != null) {
      CoverageStore store = test.getCoverageStore();
      if (store instanceof CoverageWindow) {
        CoverageWindow window = (CoverageWindow) store;
        if (!window.isClosed()) {
          return window;
        }
      }
    }
    return null;
  }

  /**
   * Opens a window on the current thread, whose probe arrays are {@code threadProbes}.
   *
   * @return the window that was the innermost open on the thread
   */
  @Nullable
  static CoverageWindow openWindow(ThreadProbes threadProbes, CoverageWindow window) {
    if (!threadProbes.isPrimary()) {
      synchronized (LOCK) {
        if (primary == null) {
          primary = threadProbes;
          threadProbes.usePrimaryTable(PRIMARY);
        }
      }
    }
    return threadProbes.open(window);
  }

  private static ThreadProbes register(ThreadProbes threadProbes) {
    ThreadProbes[] dead = null;
    int deadCount = 0;
    synchronized (LOCK) {
      if (registeredCount == registered.length) {
        int kept = 0;
        for (int i = 0; i < registeredCount; i++) {
          ThreadProbes candidate = registered[i];
          if (candidate.thread.isAlive()) {
            registered[kept++] = candidate;
          } else {
            if (dead == null) {
              dead = new ThreadProbes[registeredCount];
            }
            dead[deadCount++] = candidate;
          }
        }
        Arrays.fill(registered, kept, registeredCount, null);
        registeredCount = kept;
        if (kept > registered.length / 2) {
          registered = Arrays.copyOf(registered, registered.length * 2);
        }
      }
      registered[registeredCount++] = threadProbes;
    }
    for (int i = 0; i < deadCount; i++) {
      dead[i].flushDead();
    }
    return threadProbes;
  }
}
