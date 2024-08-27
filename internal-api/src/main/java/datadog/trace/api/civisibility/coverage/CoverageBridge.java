package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;

public abstract class CoverageBridge {

  public static final String COVERAGE_PROBES_FIELD_NAME = "__datadogCoverageProbes";

  private static final MethodHandle COVERAGE_PROBES_GETTER;
  private static final MethodHandle COVERAGE_PROBES_SETTER;

  static {
    MethodHandles methodHandles = new MethodHandles(null);
    COVERAGE_PROBES_GETTER =
        methodHandles.privateFieldGetter(Thread.class, COVERAGE_PROBES_FIELD_NAME);
    COVERAGE_PROBES_SETTER =
        methodHandles.privateFieldSetter(Thread.class, COVERAGE_PROBES_FIELD_NAME);
  }

  private static final ThreadLocal<CoverageProbes> COVERAGE_PROBES = new ThreadLocal<>();

  private static volatile CoverageStore.Registry COVERAGE_STORE_REGISTRY;
  private static volatile CoverageDataSupplier COVERAGE_DATA_SUPPLIER;

  public static void registerCoverageDataSupplier(CoverageDataSupplier coverageDataSupplier) {
    COVERAGE_DATA_SUPPLIER = coverageDataSupplier;
  }

  public static byte[] getCoverageData() {
    return COVERAGE_DATA_SUPPLIER != null ? COVERAGE_DATA_SUPPLIER.get() : null;
  }

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
     * First one of the two faster methods to obtain the coverage storage
     */
    CoverageProbes probes;
    if (COVERAGE_PROBES_GETTER != null) {
      try {
        /*
         * Use the dedicated field injected into java.lang.Thread class
         */
        probes =
            (CoverageProbes) ((Object) COVERAGE_PROBES_GETTER.invokeExact(Thread.currentThread()));
      } catch (Throwable e) {
        throw new RuntimeException("Could not get coverage store", e);
      }
    } else {
      /*
       * The dedicated field was not injected into java.lang.Thread. Use thread local instead
       */
      probes = COVERAGE_PROBES.get();
    }

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
    }

    return NoOpProbes.INSTANCE;
  }

  public static void setThreadLocalCoverageProbes(CoverageProbes probes) {
    if (COVERAGE_PROBES_SETTER != null) {
      try {
        COVERAGE_PROBES_SETTER.invokeExact(Thread.currentThread(), (Object) probes);
      } catch (Throwable e) {
        throw new RuntimeException("Could not set coverage store", e);
      }
    } else {
      COVERAGE_PROBES.set(probes);
    }
  }

  public static void removeThreadLocalCoverageProbes() {
    if (COVERAGE_PROBES_SETTER != null) {
      try {
        COVERAGE_PROBES_SETTER.invokeExact(Thread.currentThread(), (Object) null);
      } catch (Throwable e) {
        throw new RuntimeException("Could not remove coverage store", e);
      }
    } else {
      COVERAGE_PROBES.remove();
    }
  }
}
