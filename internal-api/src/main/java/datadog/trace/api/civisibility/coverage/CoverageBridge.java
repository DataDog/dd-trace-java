package datadog.trace.api.civisibility.coverage;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public abstract class CoverageBridge {
  /*
   * While it is possible to use activeSpan() to get current coverage store, it adds a lot of overhead.
   * This thread local is here as a shortcut for hot code paths.
   */
  private static final ThreadLocal<CoverageProbeStore> COVERAGE_PROBE_STORE = new ThreadLocal<>();
  private static volatile CoverageProbeStore.Registry COVERAGE_PROBE_STORE_REGISTRY;
  private static volatile CoverageDataSupplier COVERAGE_DATA_SUPPLIER;

  public static void registerCoverageProbeStoreRegistry(
      CoverageProbeStore.Registry coverageProbeStoreRegistry) {
    COVERAGE_PROBE_STORE_REGISTRY = coverageProbeStoreRegistry;
  }

  public static CoverageProbeStore.Registry getCoverageProbeStoreRegistry() {
    return COVERAGE_PROBE_STORE_REGISTRY;
  }

  public static void registerCoverageDataSupplier(CoverageDataSupplier coverageDataSupplier) {
    COVERAGE_DATA_SUPPLIER = coverageDataSupplier;
  }

  public static byte[] getCoverageData() {
    return COVERAGE_DATA_SUPPLIER != null ? COVERAGE_DATA_SUPPLIER.get() : null;
  }

  public static void setThreadLocalCoverageProbeStore(CoverageProbeStore probes) {
    COVERAGE_PROBE_STORE.set(probes);
  }

  public static void removeThreadLocalCoverageProbeStore() {
    COVERAGE_PROBE_STORE.remove();
  }

  /* This method is referenced by name in bytecode added in jacoco instrumentation module */
  public static void currentCoverageProbeStoreRecord(Class<?> clazz, long classId, int probeId) {
    CoverageProbeStore probes = COVERAGE_PROBE_STORE.get();
    if (probes != null) {
      probes.record(clazz, classId, probeId);
    } else {
      probes = getCurrentCoverageProbeStore();
      if (probes != null) {
        probes.record(clazz, classId, probeId);
      }
    }
  }

  /* This method is referenced by name in bytecode added by coverage probes (see CoverageUtils) */
  public static void currentCoverageProbeStoreRecord(Class<?> clazz) {
    CoverageProbeStore probes = COVERAGE_PROBE_STORE.get();
    if (probes != null) {
      probes.record(clazz);
    } else {
      probes = getCurrentCoverageProbeStore();
      if (probes != null) {
        probes.record(clazz);
      }
    }
  }

  public static void currentCoverageProbeStoreRecordNonCode(String absolutePath) {
    CoverageProbeStore probes = COVERAGE_PROBE_STORE.get();
    if (probes != null) {
      probes.recordNonCodeResource(absolutePath);
    } else {
      probes = getCurrentCoverageProbeStore();
      if (probes != null) {
        probes.recordNonCodeResource(absolutePath);
      }
    }
  }

  /**
   * Gets coverage probe store associated with the active span. This is a fallback method for cases
   * when the probe store could not be retrieved from the thread local. This can happen if the span
   * is propagated from the original test thread to another thread.
   */
  private static CoverageProbeStore getCurrentCoverageProbeStore() {
    AgentSpan span = activeSpan();
    if (span == null) {
      return null;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      return null;
    }
    return requestContext.getData(RequestContextSlot.CI_VISIBILITY);
  }
}
