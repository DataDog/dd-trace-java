package datadog.trace.api.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.file.Path;

public abstract class InstrumentationBridge {

  public static final String ITR_SKIP_REASON = "Skipped by Datadog Intelligent Test Runner";
  public static final String ITR_UNSKIPPABLE_TAG = "datadog_itr_unskippable";

  /*
   * While it is possible to use activeSpan() to get current coverage store, it adds a lot of overhead.
   * This thread local is here as a shortcut for hot code paths.
   */
  private static final ThreadLocal<CoverageProbeStore> COVERAGE_PROBE_STORE = new ThreadLocal<>();
  private static volatile TestEventsHandler.Factory TEST_EVENTS_HANDLER_FACTORY;
  private static volatile BuildEventsHandler.Factory BUILD_EVENTS_HANDLER_FACTORY;
  private static volatile CoverageProbeStore.Registry COVERAGE_PROBE_STORE_REGISTRY;
  private static volatile CoverageDataSupplier COVERAGE_DATA_SUPPLIER;

  public static void registerTestEventsHandlerFactory(
      TestEventsHandler.Factory testEventsHandlerFactory) {
    TEST_EVENTS_HANDLER_FACTORY = testEventsHandlerFactory;
  }

  public static TestEventsHandler createTestEventsHandler(String component, Path path) {
    return TEST_EVENTS_HANDLER_FACTORY.create(component, path);
  }

  public static void registerBuildEventsHandlerFactory(
      BuildEventsHandler.Factory buildEventsHandlerFactory) {
    BUILD_EVENTS_HANDLER_FACTORY = buildEventsHandlerFactory;
  }

  public static <U> BuildEventsHandler<U> createBuildEventsHandler() {
    return BUILD_EVENTS_HANDLER_FACTORY.create();
  }

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

  /* This method is referenced by name in bytecode added in the jacoco module */
  public static void currentCoverageProbeStoreRecord(Class<?> clazz, long classId, int probeId) {
    CoverageProbeStore probes = COVERAGE_PROBE_STORE.get();
    if (probes != null) {
      probes.record(clazz, classId, probeId);
    } else {
      // No thread-local coverage store means that the call is outside the scope of a test,
      // or that the test started in a different thread.
      // Fall back to trying to retrieve coverage store from active span
      // (which could propagate from the thread that started the test to this thread)
      probes = getCurrentCoverageProbeStore();
      if (probes != null) {
        probes.record(clazz, classId, probeId);
      }
    }
  }

  public static void currentCoverageProbeStoreRecordNonCode(String absolutePath) {
    CoverageProbeStore probes = getCurrentCoverageProbeStore();
    if (probes != null) {
      probes.recordNonCodeResource(absolutePath);
    }
  }

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
