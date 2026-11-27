package datadog.trace.api.civisibility.coverage;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.environment.SystemProperties;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>A thread finds its arrays by its id, which {@link ThreadIds} reads without calling a JDK
 * method that has a body, and the recorder registers them until the thread dies. Code that JDK
 * methods run, such as a mocking library's advice in an instrumented JDK class, has probes too, so
 * between a probe's call and the point where the recorder refuses to enter itself again only
 * bytecode and native methods run: see {@link #register} and {@link ThreadProbes#take}.
 */
public final class ProbeRecorder {

  /** Upper bound on instrumented classes per process; classes beyond it are not instrumented. */
  public static final int MAX_CLASSES = 1 << 16;

  /** Names a directory that each process writes counts of the probe collector's work to at exit. */
  public static final String STATS_DIRECTORY_PROPERTY =
      "dd.civisibility.experiment.patcher.stats.dir";

  /** Whether threads count the methods they start with no window open. */
  static final boolean COUNTING_ENTRIES = SystemProperties.get(STATS_DIRECTORY_PROPERTY) != null;

  /** Probe arrays of class initializers, indexed by class id. */
  @SuppressFBWarnings(
      value = "PA_PUBLIC_ARRAY_ATTRIBUTE",
      justification = "the class initializers of instrumented classes read it with getstatic")
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
   * the window the thread was handed until it registers its arrays, which then hold that window,
   * and null after.
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

  /** The open windows of tests, by the id of their test's trace (see {@link #testWindow}). */
  private static final ConcurrentHashMap<DDTraceId, CoverageWindow> TRACE_WINDOWS =
      new ConcurrentHashMap<>();

  private static final Object LOCK = new Object();

  // guarded by LOCK
  private static ThreadProbes primary;

  /**
   * The arrays of each thread, by the thread's id, with linear probing. Threads read it without a
   * lock. It is written under LOCK, only by putting arrays in a free slot or by replacing the table
   * with a new one, and at most half its slots are used.
   */
  private static volatile ThreadProbes[] registered = new ThreadProbes[128];

  // guarded by LOCK
  private static int registeredCount;
  private static int pruneAt = 64;
  private static long createdThreadProbes;
  private static long droppedEntriesOfRemovedThreads;
  private static long noWindowEntriesOfRemovedThreads;

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
   * Methods that started inside the recorder's own work on a thread, such as a move of its hits,
   * and whose hits were dropped (see {@link ThreadProbes}). Exact for threads that are not running.
   */
  public static long getDroppedEntryCount() {
    synchronized (LOCK) {
      long count = droppedEntriesOfRemovedThreads;
      for (ThreadProbes threadProbes : registered) {
        if (threadProbes != null) {
          count += threadProbes.droppedEntries();
        }
      }
      return count;
    }
  }

  /**
   * Methods that threads started with no window open, which look up their owner; counted only if
   * {@link #STATS_DIRECTORY_PROPERTY} is set. Exact for threads that are not running.
   */
  public static long getNoWindowEntryCount() {
    synchronized (LOCK) {
      long count = noWindowEntriesOfRemovedThreads;
      for (ThreadProbes threadProbes : registered) {
        if (threadProbes != null) {
          count += threadProbes.noWindowEntries();
        }
      }
      return count;
    }
  }

  /** Threads that got probe arrays of their own. */
  public static long getCreatedThreadProbesCount() {
    synchronized (LOCK) {
      return createdThreadProbes;
    }
  }

  /** Threads whose probe arrays are registered, counting those that died since the last prune. */
  public static int getRegisteredThreadProbesCount() {
    synchronized (LOCK) {
      return registeredCount;
    }
  }

  /**
   * Called by instrumented code at method entry: the current thread's probe array of a class. Slot
   * 0 is stored into by exception handlers that ran before any probe and is never read.
   */
  public static boolean[] probes(int classId) {
    Thread thread = Thread.currentThread();
    if (thread == primaryThread) {
      boolean[] probes = PRIMARY[classId];
      if (probes != null) {
        return probes;
      }
    }
    return threadProbes(thread, classId);
  }

  private static boolean[] threadProbes(Thread thread, int classId) {
    try {
      long threadId = ThreadIds.of(thread);
      ThreadProbes threadProbes = registeredFor(threadId);
      if (threadProbes == null) {
        threadProbes = register(thread, threadId);
      }
      return threadProbes.take(classId);
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
      all = new ThreadProbes[registeredCount];
      int count = 0;
      for (ThreadProbes threadProbes : registered) {
        if (threadProbes != null) {
          all[count++] = threadProbes;
        }
      }
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

  /**
   * Whether the recorder can find the arrays of threads, which it does by thread id. On JDK 9 and
   * later, call it only once {@code java.base} exports {@code jdk.internal.misc} to this class: the
   * answer does not change after the first call.
   */
  public static boolean isAvailable() {
    return ThreadIds.AVAILABLE;
  }

  /** The probe arrays of the current thread. */
  static ThreadProbes threadProbes() {
    Thread thread = Thread.currentThread();
    long threadId = ThreadIds.of(thread);
    ThreadProbes threadProbes = registeredFor(threadId);
    return threadProbes != null ? threadProbes : register(thread, threadId);
  }

  /**
   * Restores the entry of {@link #CREATOR} on the current thread, which hands threads it creates
   * their window, once the JDK erased its thread locals, as it does to some pool workers.
   */
  static void keepCreatorEntry() {
    CREATOR.get();
  }

  /**
   * The window of the test whose trace the current thread's context is in, if the test has one and
   * it is open.
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
      ThreadProbes threadProbes = registeredFor(ThreadIds.of(Thread.currentThread()));
      boolean wasBusy = threadProbes != null && threadProbes.enter();
      try {
        CoverageWindow window;
        if (threadProbes != null) {
          window = threadProbes.currentOwner();
        } else {
          // the current thread has run no instrumented code, so no window is open on it
          window = contextWindow();
          if (window == null && creator instanceof CoverageWindow) {
            window = (CoverageWindow) creator;
          }
        }
        return window != null && !window.isClosed() && !createsPoolWorker() ? window : null;
      } finally {
        if (threadProbes != null) {
          threadProbes.exit(wasBusy);
        }
      }
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

  /**
   * The window of the test whose trace the active span is in: open or closed if the test's span is
   * the span's local root; while it is open, if the span is under another local root, such as the
   * span of an in-process server that the test calls through an instrumented client.
   */
  @Nullable
  private static CoverageWindow testWindow() {
    AgentSpan span = activeSpan();
    if (span == null) {
      return null;
    }
    TestContext test = InstrumentationTestBridge.getTestContext(span);
    if (test != null) {
      CoverageStore store = test.getCoverageStore();
      return store instanceof CoverageWindow ? (CoverageWindow) store : null;
    }
    return TRACE_WINDOWS.get(span.getTraceId());
  }

  /** Makes {@code window} the window of the test whose trace has the id {@code traceId}. */
  static void bindTrace(DDTraceId traceId, CoverageWindow window) {
    TRACE_WINDOWS.put(traceId, window);
  }

  /** Called when {@code window} closes. */
  static void unbindTrace(DDTraceId traceId, CoverageWindow window) {
    TRACE_WINDOWS.remove(traceId, window);
  }

  /**
   * Called after a scope becomes active on the current thread. On a thread with a window open, the
   * code that a scope carrying another test's context runs belongs to that test while its window is
   * open, and to Shared after it closed. A scope that carries no test leaves the owner as it is.
   */
  static void scopeActivated() {
    ThreadProbes threadProbes = registeredFor(ThreadIds.of(Thread.currentThread()));
    if (threadProbes != null && threadProbes.hasOpenWindow() && !threadProbes.isBusy()) {
      CoverageWindow window = testWindow();
      if (window != null) {
        threadProbes.scopeActivated(window);
      }
    }
  }

  /** Called after a scope closes on the current thread. */
  static void scopeClosed() {
    ThreadProbes threadProbes = registeredFor(ThreadIds.of(Thread.currentThread()));
    if (threadProbes != null && !threadProbes.isBusy()) {
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

  /**
   * Registers new arrays for the current thread, handed the window that {@link #CREATOR} holds.
   * Until they are in the table, only bytecode runs, so that code a JDK method runs meanwhile, such
   * as a mocking library's advice, finds them rather than registering again. From then on, the
   * thread is busy until they are ready, which drops the hits of such code. If a JDK method throws
   * before they are attached to the thread, they are removed, so that the thread registers again at
   * its next method entry, with the window it was handed.
   */
  private static ThreadProbes register(Thread thread, long threadId) {
    ThreadProbes threadProbes = new ThreadProbes(threadId);
    ThreadProbes[] dead = null;
    try {
      synchronized (LOCK) {
        ThreadProbes[] table = registered;
        if (registeredCount >= table.length / 2) {
          table = rehashed(table, table.length * 2);
          registered = table;
        }
        insert(table, threadProbes);
        registeredCount++;
        createdThreadProbes++;
        if (registeredCount >= pruneAt) {
          dead = prune();
        }
      }
      Object handed = CREATOR.get();
      threadProbes.attach(
          thread, handed instanceof CoverageWindow ? (CoverageWindow) handed : null);
      CREATOR.set(null);
    } finally {
      try {
        if (dead != null) {
          flushDead(dead);
        }
      } finally {
        if (!threadProbes.isAttached()) {
          unregister(threadProbes);
        }
        threadProbes.exit(false);
      }
    }
    return threadProbes;
  }

  /** Moves the hits of the arrays that a prune removed, which are followed by nulls. */
  private static void flushDead(ThreadProbes[] dead) {
    for (ThreadProbes deadThreadProbes : dead) {
      if (deadThreadProbes == null) {
        break;
      }
      deadThreadProbes.flushDead();
    }
  }

  /** Removes arrays from the table, if they are in it. Only bytecode runs. */
  private static void unregister(ThreadProbes threadProbes) {
    synchronized (LOCK) {
      ThreadProbes[] table = registered;
      ThreadProbes[] rebuilt = new ThreadProbes[table.length];
      boolean found = false;
      for (ThreadProbes candidate : table) {
        if (candidate == threadProbes) {
          found = true;
        } else if (candidate != null) {
          insert(rebuilt, candidate);
        }
      }
      if (found) {
        registered = rebuilt;
        registeredCount--;
        droppedEntriesOfRemovedThreads += threadProbes.droppedEntries();
      }
    }
  }

  /** The arrays registered for the thread with the given id, or null. */
  @Nullable
  private static ThreadProbes registeredFor(long threadId) {
    ThreadProbes[] table = registered;
    int mask = table.length - 1;
    for (int index = spread(threadId) & mask; ; index = (index + 1) & mask) {
      ThreadProbes candidate = table[index];
      if (candidate == null || candidate.threadId == threadId) {
        return candidate;
      }
    }
  }

  /** Puts arrays in a free slot of a table. Holds {@link #LOCK}. */
  private static void insert(ThreadProbes[] table, ThreadProbes threadProbes) {
    int mask = table.length - 1;
    int index = spread(threadProbes.threadId) & mask;
    while (table[index] != null) {
      index = (index + 1) & mask;
    }
    table[index] = threadProbes;
  }

  /** The arrays of a table in a new table with {@code length} slots. Holds {@link #LOCK}. */
  private static ThreadProbes[] rehashed(ThreadProbes[] table, int length) {
    ThreadProbes[] rehashed = new ThreadProbes[length];
    for (ThreadProbes threadProbes : table) {
      if (threadProbes != null) {
        insert(rehashed, threadProbes);
      }
    }
    return rehashed;
  }

  private static int spread(long threadId) {
    int hash = (int) (threadId ^ (threadId >>> 32)) * 0x9E3779B9;
    return hash ^ (hash >>> 16);
  }

  /**
   * Removes the arrays of the threads that died, which keeps the table bounded by the threads that
   * live, and leaves at least a quarter of it to fill before the next prune. Holds {@link #LOCK},
   * and is called by a registering thread once its arrays are in the table.
   *
   * @return the removed arrays, followed by nulls, to flush once the lock is released; or null
   */
  @Nullable
  private static ThreadProbes[] prune() {
    ThreadProbes[] alive = new ThreadProbes[registeredCount];
    int aliveCount = 0;
    ThreadProbes[] dead = null;
    int deadCount = 0;
    for (ThreadProbes candidate : registered) {
      if (candidate == null) {
        continue;
      }
      if (candidate.isThreadAlive()) {
        alive[aliveCount++] = candidate;
      } else {
        droppedEntriesOfRemovedThreads += candidate.droppedEntries();
        noWindowEntriesOfRemovedThreads += candidate.noWindowEntries();
        if (dead == null) {
          dead = new ThreadProbes[registeredCount];
        }
        dead[deadCount++] = candidate;
      }
    }
    int length = registered.length;
    while (aliveCount > length / 4) {
      length *= 2;
    }
    ThreadProbes[] table = new ThreadProbes[length];
    for (int i = 0; i < aliveCount; i++) {
      insert(table, alive[i]);
    }
    registered = table;
    registeredCount = aliveCount;
    pruneAt = length / 2;
    return dead;
  }
}
