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

  /* This method is referenced by name in bytecode added in the jacoco module */
  public static void currentCoverageProbeStoreRecord(
      long classId, String className, Class<?> clazz, int probeId) {
    AgentSpan span = activeSpan();
    if (span == null) {
      return;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      return;
    }
    CoverageProbeStore probes = requestContext.getData(RequestContextSlot.CI_VISIBILITY);
    if (probes != null) {
      probes.record(clazz, classId, className, probeId);
    }
  }
}
