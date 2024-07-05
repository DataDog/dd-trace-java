package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;

public abstract class CoverageBridge {

  private static volatile CoverageStore.Registry COVERAGE_PROBE_STORE_REGISTRY;
  private static volatile CoverageDataSupplier COVERAGE_DATA_SUPPLIER;

  /**
   * While it is possible to use activeSpan() to get current coverage store, it adds a lot of
   * overhead. To reduce the overhead the tracer injects this field into the {@link Thread} class.
   * It is, essentially, a faster {@link ThreadLocal}.
   */
  public static final String COVERAGE_STORE_FIELD_NAME = "__datadogCoverageStore";

  private static final MethodHandle COVERAGE_STORE_GETTER;
  private static final MethodHandle COVERAGE_STORE_SETTER;

  static {
    MethodHandles methodHandles = new MethodHandles(null);
    COVERAGE_STORE_GETTER =
        methodHandles.privateFieldGetter(Thread.class, COVERAGE_STORE_FIELD_NAME);
    COVERAGE_STORE_SETTER =
        methodHandles.privateFieldSetter(Thread.class, COVERAGE_STORE_FIELD_NAME);
  }

  /**
   * Used as a fallback in situations where {@link CoverageBridge#COVERAGE_STORE_FIELD_NAME} could
   * not be injected.
   */
  private static final ThreadLocal<CoverageStore> THREAD_LOCAL_COVERAGE_STORE = new ThreadLocal<>();

  public static void registerCoverageProbeStoreRegistry(
      CoverageStore.Registry coverageProbeStoreRegistry) {
    COVERAGE_PROBE_STORE_REGISTRY = coverageProbeStoreRegistry;
  }

  public static CoverageStore.Registry getCoverageProbeStoreRegistry() {
    return COVERAGE_PROBE_STORE_REGISTRY;
  }

  public static void registerCoverageDataSupplier(CoverageDataSupplier coverageDataSupplier) {
    COVERAGE_DATA_SUPPLIER = coverageDataSupplier;
  }

  public static byte[] getCoverageData() {
    return COVERAGE_DATA_SUPPLIER != null ? COVERAGE_DATA_SUPPLIER.get() : null;
  }

  public static void pinCoverageStore(CoverageStore probes) {
    if (COVERAGE_STORE_SETTER != null) {
      try {
        COVERAGE_STORE_SETTER.invokeExact(Thread.currentThread(), (Object) probes);
      } catch (Throwable e) {
        throw new RuntimeException("Could not pin coverage store", e);
      }

    } else {
      THREAD_LOCAL_COVERAGE_STORE.set(probes);
    }
  }

  public static void unpinCoverageStore() {
    if (COVERAGE_STORE_SETTER != null) {
      try {
        COVERAGE_STORE_SETTER.invokeExact(Thread.currentThread(), (Object) null);
      } catch (Throwable e) {
        throw new RuntimeException("Could not unpin coverage store", e);
      }

    } else {
      THREAD_LOCAL_COVERAGE_STORE.remove();
    }
  }

  /* This method is referenced by name in bytecode added in jacoco instrumentation module */
  public static void currentCoverageProbeStoreRecord(Class<?> clazz, long classId, int probeId) {
    CoverageStore probes;
    if (COVERAGE_STORE_GETTER != null) {
      try {
        probes =
            (CoverageStore) ((Object) COVERAGE_STORE_GETTER.invokeExact(Thread.currentThread()));
      } catch (Throwable e) {
        throw new RuntimeException("Could not get coverage store", e);
      }
    } else {
      probes = THREAD_LOCAL_COVERAGE_STORE.get();
    }

    if (probes == null) {
      probes = getCurrentCoverageProbeStore();
    }

    if (probes != null) {
      probes.record(clazz, classId, probeId);
    }
  }

  /* This method is referenced by name in bytecode added by coverage probes (see CoverageUtils) */
  public static void currentCoverageProbeStoreRecord(Class<?> clazz) {
    CoverageStore probes;
    if (COVERAGE_STORE_GETTER != null) {
      try {
        probes =
            (CoverageStore) ((Object) COVERAGE_STORE_GETTER.invokeExact(Thread.currentThread()));
      } catch (Throwable e) {
        throw new RuntimeException("Could not get coverage store", e);
      }
    } else {
      probes = THREAD_LOCAL_COVERAGE_STORE.get();
    }

    if (probes == null) {
      probes = getCurrentCoverageProbeStore();
    }

    if (probes != null) {
      probes.record(clazz);
    }
  }

  public static void currentCoverageProbeStoreRecordNonCode(String absolutePath) {
    CoverageStore probes;
    if (COVERAGE_STORE_GETTER != null) {
      try {
        probes =
            (CoverageStore) ((Object) COVERAGE_STORE_GETTER.invokeExact(Thread.currentThread()));
      } catch (Throwable e) {
        throw new RuntimeException("Could not get coverage store", e);
      }
    } else {
      probes = THREAD_LOCAL_COVERAGE_STORE.get();
    }

    if (probes == null) {
      probes = getCurrentCoverageProbeStore();
    }

    if (probes != null) {
      probes.recordNonCodeResource(absolutePath);
    }
  }

  /**
   * Gets coverage probe store associated with the active span. This is a fallback method for cases
   * when the probe store could not be retrieved via faster methods. This can happen if the span is
   * propagated from the original test thread to another thread.
   */
  private static CoverageStore getCurrentCoverageProbeStore() {
    TestContext currentTest = InstrumentationTestBridge.getCurrentTestContext();
    if (currentTest != null) {
      return currentTest.getCoverageProbeStore();
    } else {
      return null;
    }
  }
}
