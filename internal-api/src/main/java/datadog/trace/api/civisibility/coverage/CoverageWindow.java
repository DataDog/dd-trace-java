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
 * <p>A window opens on the thread that creates it and closes in {@link #close()}, which collects
 * the probes the test hit and resets their slots for the next window that uses the same table (see
 * {@link ProbeRecorder}). Code running on other threads on behalf of the test can still be marking
 * probes while the window closes, so those threads register as writers and {@link #close()} waits
 * for them.
 */
public abstract class CoverageWindow {

  private final Thread thread = Thread.currentThread();
  private final AtomicInteger writers = new AtomicInteger();
  private final Object touchedClassesLock = new Object();
  private volatile boolean closed;

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
    if (closed) {
      return null;
    }
    boolean[] probes = table[classId];
    return probes != null ? probes : ProbeRecorder.allocate(table, classId);
  }

  /**
   * @return {@code false} if the window is already closed
   */
  final boolean mark(int classId, int probeId) {
    if (Thread.currentThread() == thread) {
      if (closed) {
        return false;
      }
      set(classId, probeId);
      return true;
    }
    if (closed) {
      return false;
    }
    writers.incrementAndGet();
    try {
      if (closed) {
        return false;
      }
      set(classId, probeId);
      return true;
    } finally {
      writers.decrementAndGet();
    }
  }

  private void set(int classId, int probeId) {
    boolean[] probes = table[classId];
    if (probes == null) {
      probes = ProbeRecorder.allocate(table, classId);
    }
    probes[probeId] = true;
    if (!probes[0]) {
      probes[0] = true;
      addTouchedClass(classId);
    }
  }

  private void addTouchedClass(int classId) {
    synchronized (touchedClassesLock) {
      if (touchedClassCount == touchedClasses.length) {
        touchedClasses = Arrays.copyOf(touchedClasses, touchedClassCount * 2);
      }
      touchedClasses[touchedClassCount++] = classId;
    }
  }

  public final boolean isClosed() {
    return closed;
  }

  /**
   * Closes the window. Must be called once, on the thread that opened it: until another thread
   * closing it is seen by the opening thread, that thread can skip probes that the next window hit.
   *
   * @return the probes hit while the window was open, one entry per class
   */
  protected final List<ClassHits> close() {
    closed = true;
    while (writers.get() != 0) {
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
      if (!probes[0]) {
        continue; // the same class was added twice by racing threads
      }
      probes[0] = false;

      InstrumentedClass instrumentedClass = ProbeRecorder.CLASSES[classId];
      int end = instrumentedClass.initStart();
      long[] bits = new long[(end + 63) >>> 6];
      for (int probe = 1; probe < end; probe++) {
        if (probes[probe]) {
          probes[probe] = false;
          bits[probe >>> 6] |= 1L << probe;
        }
      }
      hits.add(new ClassHits(classId, instrumentedClass, bits));
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
