package datadog.trace.api.civisibility.coverage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * The period during which probe hits are credited to one test.
 *
 * <p>A window opens on the thread that creates it and closes in {@link #close()}, on the same
 * thread. Closing collects the probes the test hit and drops the window's probe arrays, so the next
 * window that uses the same table allocates new ones (see {@link ProbeRecorder}). Code running on
 * other threads on behalf of the test can be taking arrays from the table while the window closes,
 * so those threads register while they do and {@link #close()} waits for them: no thread can take
 * an array that the table holds for a later window.
 */
public abstract class CoverageWindow {

  private final Thread thread = Thread.currentThread();
  private final AtomicInteger otherThreadsTakingProbes = new AtomicInteger();
  private final Object touchedClassesLock = new Object();
  private volatile boolean closed;

  // guarded by touchedClassesLock
  private int[] touchedClasses = new int[32];
  private int touchedClassCount;

  @Nullable private final CoverageWindow outer;
  private final boolean[][] table;

  /** The primary window whose thread this window took over, guarded by the recorder's lock. */
  @Nullable CoverageWindow suspendedPrimary;

  protected CoverageWindow() {
    outer = ProbeRecorder.ownWindow();
    table = ProbeRecorder.openWindow(this);
  }

  final Thread thread() {
    return thread;
  }

  /**
   * @return the probe array of a class in this window, or {@code null} if the window is closed
   */
  @Nullable
  final boolean[] probes(int classId) {
    if (Thread.currentThread() == thread) {
      return closed ? null : probesWhileOpen(classId);
    }
    if (closed) {
      return null;
    }
    otherThreadsTakingProbes.incrementAndGet();
    try {
      return closed ? null : probesWhileOpen(classId);
    } finally {
      otherThreadsTakingProbes.decrementAndGet();
    }
  }

  private boolean[] probesWhileOpen(int classId) {
    boolean[] probes = table[classId];
    if (probes != null) {
      return probes;
    }
    synchronized (touchedClassesLock) {
      probes = table[classId];
      if (probes == null) {
        probes = ProbeRecorder.newProbes(classId);
        table[classId] = probes;
        if (touchedClassCount == touchedClasses.length) {
          touchedClasses = Arrays.copyOf(touchedClasses, touchedClassCount * 2);
        }
        touchedClasses[touchedClassCount++] = classId;
      }
      return probes;
    }
  }

  public final boolean isClosed() {
    return closed;
  }

  /**
   * Closes the window. Must be called once, on the thread that opened it.
   *
   * @return the probes hit while the window was open, one entry per class
   */
  protected final List<ClassHits> close() {
    closed = true;
    while (otherThreadsTakingProbes.get() != 0) {
      Thread.yield();
    }

    int[] classIds;
    int classCount;
    synchronized (touchedClassesLock) {
      classIds = touchedClasses;
      classCount = touchedClassCount;
    }
    List<ClassHits> hits = classCount == 0 ? Collections.emptyList() : new ArrayList<>(classCount);
    for (int i = 0; i < classCount; i++) {
      int classId = classIds[i];
      boolean[] probes = table[classId];
      // code still running with this array stores into it where no window reads
      table[classId] = null;
      InstrumentedClass instrumentedClass = ProbeRecorder.CLASSES[classId];
      long[] bits = instrumentedClass.methodHits(probes);
      if (bits != null) {
        hits.add(new ClassHits(classId, instrumentedClass, bits));
      }
    }
    ProbeRecorder.closeWindow(this, table, outer);
    return hits;
  }

  /** The probes one class had hit in a window. */
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
