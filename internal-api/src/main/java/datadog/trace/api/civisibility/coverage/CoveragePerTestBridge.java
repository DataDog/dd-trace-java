package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;

public abstract class CoveragePerTestBridge {

  private static final ThreadLocal<CoverageProbes> COVERAGE_PROBES = new ThreadLocal<>();

  private static volatile CoverageStore.Registry COVERAGE_STORE_REGISTRY;

  public static void registerCoverageStoreRegistry(CoverageStore.Registry coverageStoreRegistry) {
    COVERAGE_STORE_REGISTRY = coverageStoreRegistry;
  }

  public static CoverageStore.Registry getCoverageStoreRegistry() {
    return COVERAGE_STORE_REGISTRY;
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
