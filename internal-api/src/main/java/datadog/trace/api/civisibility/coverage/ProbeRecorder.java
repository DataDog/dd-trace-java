package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
 * describes how the hits reach their owner. Shared is never reset: it is reported once per process,
 * in the parts of {@link SharedPart}.
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

  /**
   * Probes hit by code that no open window owned, allocated on first use: the unowned part of
   * Shared.
   */
  private static final boolean[][] SHARED = new boolean[MAX_CLASSES][];

  /** Probes hit by suite setup, allocated on first use: the suite setup part of Shared. */
  private static final boolean[][] SUITE_SETUP = new boolean[MAX_CLASSES][];

  /**
   * Hands a new thread the window that owns the code of the thread that creates it. The value is
   * the thread's {@link ThreadProbes} once it has them, and before that the window the thread was
   * handed, or null.
   */
  private static final InheritableThreadLocal<Object> CREATOR =
      new InheritableThreadLocal<Object>() {
        @Override
        protected Object childValue(Object creator) {
          // called by the creating thread while it constructs the new thread
          return windowForNewThread(creator);
        }
      };

  /**
   * The methods that create the worker threads of pools, by class and method name. A pool worker
   * runs tasks that other code submits, so it is handed no window: a task that runs for a test
   * carries the test's context.
   */
  private static final String[][] POOL_WORKER_CREATORS = {
    {"java.util.concurrent.ThreadPoolExecutor", "addWorker"},
    {"java.util.concurrent.ForkJoinPool", "createWorker"},
    {"java.util.Timer", "<init>"},
    {"kotlinx.coroutines.scheduling.CoroutineScheduler", "createNewWorker"},
    {"kotlinx.coroutines.DefaultExecutor", "createThreadSync"},
    {"io.netty.util.concurrent.SingleThreadEventExecutor", "doStartThread"},
    {"io.netty.util.concurrent.GlobalEventExecutor", "startThread"},
    {"io.netty.util.HashedWheelTimer", "<init>"},
    {"org.eclipse.jetty.util.thread.QueuedThreadPool", "startThread"},
    {"org.apache.tomcat.util.threads.ThreadPoolExecutor", "addWorker"},
  };

  private static final ThreadLocal<ThreadProbes> THREAD_PROBES =
      new ThreadLocal<ThreadProbes>() {
        @Override
        protected ThreadProbes initialValue() {
          Object handed = CREATOR.get();
          ThreadProbes threadProbes =
              new ThreadProbes(
                  Thread.currentThread(),
                  handed instanceof CoverageWindow ? (CoverageWindow) handed : null);
          CREATOR.set(threadProbes);
          return register(threadProbes);
        }
      };

  /**
   * The probe arrays of the current thread if it opened a window, so that scope changes on other
   * threads do not create any.
   */
  private static final ThreadLocal<ThreadProbes> WINDOW_THREAD_PROBES = new ThreadLocal<>();

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

  /**
   * Gives to the unowned part of Shared the probes that a window hit and that no test is credited
   * with.
   */
  public static void addSharedHits(List<CoverageWindow.ClassHits> hits) {
    addHits(SHARED, hits);
  }

  static void addSharedHits(int classId, long[] hits) {
    addHits(SHARED, classId, hits);
  }

  /** Gives to the suite setup part of Shared the probes that the setup of a suite hit. */
  static void addSuiteSetupHits(List<CoverageWindow.ClassHits> hits) {
    addHits(SUITE_SETUP, hits);
  }

  private static void addHits(boolean[][] table, List<CoverageWindow.ClassHits> hits) {
    for (CoverageWindow.ClassHits classHits : hits) {
      addHits(table, classHits.classId, classHits.probes);
    }
  }

  private static void addHits(boolean[][] table, int classId, long[] hits) {
    boolean[] probes = probesOf(table, classId);
    for (int word = 0; word < hits.length; word++) {
      for (long remaining = hits[word]; remaining != 0; remaining &= remaining - 1) {
        probes[(word << 6) + Long.numberOfTrailingZeros(remaining)] = true;
      }
    }
  }

  /**
   * Adds to {@code lines} the lines of a class in a part of Shared. The unowned part has only the
   * hits moved to it: see {@link #flushShared}.
   *
   * @return {@code false} if no line of the class is in the part
   */
  public static boolean addSharedLines(SharedPart part, int classId, BitSet lines) {
    InstrumentedClass instrumentedClass = CLASSES[classId];
    if (instrumentedClass == null) {
      return false;
    }
    if (part == SharedPart.INIT) {
      boolean[] probes = PROBES[classId];
      return probes != null && instrumentedClass.addInitLines(probes, lines);
    }
    boolean[] probes = (part == SharedPart.SUITE_SETUP ? SUITE_SETUP : SHARED)[classId];
    return probes != null && instrumentedClass.addMethodLines(probes, lines);
  }

  private static boolean[] probesOf(boolean[][] table, int classId) {
    boolean[] probes = table[classId];
    if (probes != null) {
      return probes;
    }
    synchronized (table) {
      probes = table[classId];
      if (probes == null) {
        probes = newProbes(classId);
        table[classId] = probes;
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
    CoverageWindow window = testWindow();
    return window != null && !window.isClosed() ? window : null;
  }

  /**
   * The window that a thread created by the current thread is handed: the window that owns the code
   * of the current thread, unless it closed or the new thread is the worker thread of a pool.
   *
   * @param creator the value of {@link #CREATOR} on the current thread
   */
  @Nullable
  private static CoverageWindow windowForNewThread(@Nullable Object creator) {
    try {
      CoverageWindow window;
      if (creator instanceof ThreadProbes) {
        window = ((ThreadProbes) creator).currentOwner();
      } else {
        // the current thread has run no instrumented code, so no window is open on it
        window = contextWindow();
        if (window == null) {
          window = (CoverageWindow) creator;
        }
      }
      return window != null && !window.isClosed() && !createsPoolWorker() ? window : null;
    } catch (Throwable e) {
      // creating a thread must never fail because of the recorder
      return null;
    }
  }

  /** Whether the current thread is creating the worker thread of a pool. */
  private static boolean createsPoolWorker() {
    // the walk stops at the first match, which for a pool is a few frames below the constructor
    return StackWalkerFactory.INSTANCE.walk(ProbeRecorder::hasPoolWorkerCreator);
  }

  private static boolean hasPoolWorkerCreator(Stream<StackTraceElement> frames) {
    return frames.anyMatch(ProbeRecorder::isPoolWorkerCreator);
  }

  private static boolean isPoolWorkerCreator(StackTraceElement frame) {
    String method = frame.getMethodName();
    for (String[] creator : POOL_WORKER_CREATORS) {
      if (creator[1].equals(method) && creator[0].equals(frame.getClassName())) {
        return true;
      }
    }
    return false;
  }

  /** The window of the test whose context the current thread carries, open or closed. */
  @Nullable
  private static CoverageWindow testWindow() {
    TestContext test = InstrumentationTestBridge.getCurrentTestContext();
    if (test != null) {
      CoverageStore store = test.getCoverageStore();
      if (store instanceof CoverageWindow) {
        return (CoverageWindow) store;
      }
    }
    return null;
  }

  /**
   * Called after a scope becomes active on the current thread. On a thread with a window open, the
   * code that a scope carrying another test's context runs belongs to that test while its window is
   * open, and to Shared after it closed. A scope that carries no test leaves the owner as it is.
   */
  static void scopeActivated() {
    ThreadProbes threadProbes = WINDOW_THREAD_PROBES.get();
    if (threadProbes != null && threadProbes.hasOpenWindow()) {
      CoverageWindow window = testWindow();
      if (window != null) {
        threadProbes.scopeActivated(window);
      }
    }
  }

  /** Called after a scope closes on the current thread. */
  static void scopeClosed() {
    ThreadProbes threadProbes = WINDOW_THREAD_PROBES.get();
    if (threadProbes != null) {
      threadProbes.scopeClosed();
    }
  }

  /**
   * Opens a window on the current thread, whose probe arrays are {@code threadProbes}.
   *
   * @return the window that was the innermost open on the thread
   */
  @Nullable
  static CoverageWindow openWindow(ThreadProbes threadProbes, CoverageWindow window) {
    WINDOW_THREAD_PROBES.set(threadProbes);
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
