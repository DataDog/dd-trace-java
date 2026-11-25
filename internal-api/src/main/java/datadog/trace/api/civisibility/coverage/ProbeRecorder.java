package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives hits from the line probes that {@code ProbeClassTransformer} inserts into application
 * classes.
 *
 * <p>Every instrumented class owns one probe array in {@link #PROBES}. A probe reads its slot and
 * calls {@link #record} only when the slot is not set, so after the first hit in a test a probe
 * costs one array read. {@link #record} sets the slot only for code that runs on behalf of the test
 * whose window is open, and the window resets the slots it set when it closes. Slot 0 of every
 * array marks the class as touched by the open window.
 */
public final class ProbeRecorder {

  /** Upper bound on instrumented classes per process; classes beyond it are not instrumented. */
  public static final int MAX_CLASSES = 1 << 16;

  /** Probe arrays indexed by class id. Read by instrumented code; never null for a used id. */
  public static final boolean[][] PROBES = new boolean[MAX_CLASSES][];

  static final InstrumentedClass[] CLASSES = new InstrumentedClass[MAX_CLASSES];

  private static final AtomicInteger NEXT_CLASS_ID = new AtomicInteger();

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

  /** Called by instrumented code on the first hit of a probe since its slot was last reset. */
  public static void record(int classId, int probeId) {
    try {
      TestContext test = InstrumentationTestBridge.getCurrentTestContext();
      if (test != null) {
        CoverageStore store = test.getCoverageStore();
        if (store instanceof CoverageWindow && ((CoverageWindow) store).mark(classId, probeId)) {
          return;
        }
      }
      CLASSES[classId].recordUnowned(probeId);
    } catch (Throwable ignored) {
      // instrumented code must never see an exception from the recorder
    }
  }

  /** Called on every execution by probes that cannot branch; records only a first hit. */
  public static void hit(int classId, int probeId) {
    try {
      if (!PROBES[classId][probeId]) {
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
}
