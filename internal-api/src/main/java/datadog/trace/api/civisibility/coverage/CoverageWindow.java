package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The period during which probe hits are credited to one test.
 *
 * <p>A window opens on the thread that creates it and closes in {@link #close()}. While it is the
 * innermost window open on that thread, it owns the code the thread starts. It also owns the code
 * other threads start while they carry the context of the test whose coverage store it is, or a
 * context of that test's trace (see {@link #bindTrace}). A thread moves what it hit to the window
 * when its owner changes (see {@link ThreadProbes}); closing the window ends what every thread runs
 * for it and collects what they moved.
 */
public abstract class CoverageWindow {

  private final ThreadProbes threadProbes;
  @Nullable private final CoverageWindow outer;
  private volatile boolean closed;

  /** The id of the test's trace, once bound. */
  @Nullable private volatile DDTraceId traceId;

  private final Object lock = new Object();

  /** Threads that ran code for the window through its test's context; null once it closed. */
  private List<ThreadProbes> otherThreads = new ArrayList<>(); // guarded by lock

  /** What threads moved to the window; null once collected. */
  private List<ClassHits> hits = new ArrayList<>(); // guarded by lock

  protected CoverageWindow() {
    this(null);
  }

  /**
   * @param replaced a window whose code and hits this window takes over, such as the window of a
   *     test's preparation when the test starts; it is closed
   */
  protected CoverageWindow(@Nullable CoverageWindow replaced) {
    threadProbes = ProbeRecorder.threadProbes();
    if (replaced != null
        && replaced.threadProbes == threadProbes
        && threadProbes.replace(replaced, this)) {
      outer = replaced.outer;
    } else {
      outer = ProbeRecorder.openWindow(threadProbes, this);
    }
    if (replaced != null) {
      List<ClassHits> replacedHits = replaced.close();
      synchronized (lock) {
        hits.addAll(replacedHits);
      }
    }
  }

  @Nullable
  final CoverageWindow outer() {
    return outer;
  }

  public final boolean isClosed() {
    return closed;
  }

  /**
   * Makes the window, while it is open, the owner of the code that runs under any span of its
   * test's trace, as under the test's own context. A span can be in the test's trace without the
   * test's span as its local root: an in-process server that the test calls through an instrumented
   * client runs its handler under a server span whose remote parent is the client's span.
   */
  protected final void bindTrace(DDTraceId testTraceId) {
    if (traceId == null && !closed) {
      traceId = testTraceId;
      ProbeRecorder.bindTrace(testTraceId, this);
      if (closed) {
        // closing may have read no id
        ProbeRecorder.unbindTrace(testTraceId, this);
      }
    }
  }

  /**
   * Registers a thread that runs code for this window through its test's context.
   *
   * @return {@code false} if the window is closed
   */
  final boolean addOtherThread(ThreadProbes other) {
    synchronized (lock) {
      if (otherThreads == null) {
        return false;
      }
      if (!containsThread(otherThreads, other)) {
        otherThreads.add(other);
      }
      return true;
    }
  }

  /**
   * Compares by identity rather than with {@code equals}, which a mocking library can advise with
   * code that takes probe arrays, under this window's lock.
   */
  private static boolean containsThread(List<ThreadProbes> threads, ThreadProbes threadProbes) {
    for (int i = 0, size = threads.size(); i < size; i++) {
      if (threads.get(i) == threadProbes) {
        return true;
      }
    }
    return false;
  }

  /**
   * Adds what a thread hit for the window. Once the window reported, the hits go to Shared.
   *
   * @return {@code false} if the window reported
   */
  final boolean addHits(List<ClassHits> moved) {
    synchronized (lock) {
      if (hits != null) {
        hits.addAll(moved);
        return true;
      }
    }
    ProbeRecorder.addSharedHits(moved);
    return false;
  }

  /**
   * Closes the window. Must be called once.
   *
   * @return the probes hit by the code the window owned, possibly several entries per class
   */
  protected final List<ClassHits> close() {
    closed = true;
    DDTraceId bound = traceId;
    if (bound != null) {
      ProbeRecorder.unbindTrace(bound, this);
    }
    List<ThreadProbes> others;
    synchronized (lock) {
      others = otherThreads;
      otherThreads = null;
    }
    if (others == null) {
      return Collections.emptyList();
    }
    threadProbes.close(this);
    for (ThreadProbes other : others) {
      other.leave(this);
    }
    synchronized (lock) {
      List<ClassHits> collected = hits;
      hits = null;
      return collected;
    }
  }

  /** The probes one class had hit for a window. */
  public static final class ClassHits {
    public final int classId;
    public final InstrumentedClass instrumentedClass;
    public final long[] probes;

    ClassHits(int classId, InstrumentedClass instrumentedClass, long[] probes) {
      this.classId = classId;
      this.instrumentedClass = instrumentedClass;
      this.probes = probes;
    }
  }
}
