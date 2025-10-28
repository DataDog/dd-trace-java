package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.ArrayDeque;
import java.util.Queue;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;

public abstract class CoveragePerTestBridge {

  private static final ThreadLocal<CoverageProbes> COVERAGE_PROBES = new ThreadLocal<>();

  private static volatile CoverageStore.Registry COVERAGE_STORE_REGISTRY;
  private static final Object COVERAGE_STORE_REGISTRY_LOCK = new Object();

  @GuardedBy("COVERAGE_STORE_REGISTRY_LOCK")
  private static final Queue<TotalProbeCount> DEFERRED_PROBE_COUNTS = new ArrayDeque<>();

  public static void registerCoverageStoreRegistry(
      @Nonnull CoverageStore.Registry coverageStoreRegistry) {
    synchronized (COVERAGE_STORE_REGISTRY_LOCK) {
      while (!DEFERRED_PROBE_COUNTS.isEmpty()) {
        TotalProbeCount c = DEFERRED_PROBE_COUNTS.poll();
        coverageStoreRegistry.setTotalProbeCount(c.className, c.count);
      }
      COVERAGE_STORE_REGISTRY = coverageStoreRegistry;
    }
  }

  /**
   * {@link #COVERAGE_STORE_REGISTRY} is set when CI Visibility is initialized. It is possible, that
   * core/internal JDK classes are loaded and transformed by Jacoco before this happens. As the
   * result this method may be called when {@link #COVERAGE_STORE_REGISTRY} is still {@code null}.
   *
   * <p>While instrumenting core/internal JDK classes with Jacoco makes little sense, we do not
   * always have the control over the users' Jacoco {@code includes} setting, therefore we have to
   * account for this case and support it.
   *
   * <p>If this method finds {@link #COVERAGE_STORE_REGISTRY} to be {@code null}, the probe counts
   * are saved in {@link #DEFERRED_PROBE_COUNTS} to be processed when {@link
   * #COVERAGE_STORE_REGISTRY} is set.
   */
  public static void setTotalProbeCount(String className, int totalProbeCount) {
    if (COVERAGE_STORE_REGISTRY != null) {
      COVERAGE_STORE_REGISTRY.setTotalProbeCount(className, totalProbeCount);
      return;
    }

    synchronized (COVERAGE_STORE_REGISTRY_LOCK) {
      if (COVERAGE_STORE_REGISTRY != null) {
        COVERAGE_STORE_REGISTRY.setTotalProbeCount(className, totalProbeCount);
      } else {
        DEFERRED_PROBE_COUNTS.offer(new TotalProbeCount(className, totalProbeCount));
      }
    }
  }

  private static final class TotalProbeCount {
    private final String className;
    private final int count;

    private TotalProbeCount(String className, int count) {
      this.className = className;
      this.count = count;
    }
  }

  /* This method is referenced by name in bytecode added in jacoco instrumentation module (see datadog.trace.instrumentation.jacoco.ProbeInserterInstrumentation.InsertProbeAdvice) */
  public static void recordCoverage(Class<?> clazz, long classId, int probeId) {
    getCurrentCoverageProbes().record(clazz, classId, probeId);
  }

  /* This method is referenced by name in bytecode added by coverage probes (see datadog.trace.civisibility.coverage.instrumentation.CoverageUtils#insertCoverageProbe) */
  public static void recordCoverage(Class<?> clazz) {
    getCurrentCoverageProbes().record(clazz);
  }

  public static void recordCoverage(String absolutePath) {
    getCurrentCoverageProbes().recordNonCodeResource(absolutePath);
  }

  private static CoverageProbes getCurrentCoverageProbes() {
    /*
     * While it is possible to use activeSpan() to get current coverage store, it adds a lot of overhead.
     * The thread local is used as a shortcut for hot code paths.
     */
    CoverageProbes probes = COVERAGE_PROBES.get();
    if (probes != null) {
      return probes;
    }

    /*
     * Get coverage probe store associated with the active span: a fallback method for cases
     * when the probe store could not be retrieved from the thread local. This can happen if the span
     * is propagated from the original test thread to another thread.
     */
    TestContext currentTest = InstrumentationTestBridge.getCurrentTestContext();
    if (currentTest != null) {
      return currentTest.getCoverageStore().getProbes();
    } else {
      return NoOpProbes.INSTANCE;
    }
  }

  public static void setThreadLocalCoverageProbes(CoverageProbes probes) {
    COVERAGE_PROBES.set(probes);
  }

  public static void removeThreadLocalCoverageProbes() {
    COVERAGE_PROBES.remove();
  }
}
