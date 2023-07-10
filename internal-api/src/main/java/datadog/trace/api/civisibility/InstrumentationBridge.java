package datadog.trace.api.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.file.Path;

public abstract class InstrumentationBridge {

  private static volatile TestEventsHandler.Factory TEST_EVENTS_HANDLER_FACTORY;
  private static volatile BuildEventsHandler.Factory BUILD_EVENTS_HANDLER_FACTORY;
  private static volatile CoverageProbeStore.Factory COVERAGE_PROBE_STORE_FACTORY;

  public static void registerTestEventsHandlerFactory(
      TestEventsHandler.Factory testEventsHandlerFactory) {
    TEST_EVENTS_HANDLER_FACTORY = testEventsHandlerFactory;
  }

  public static TestEventsHandler createTestEventsHandler(
      String component, String testFramework, String testFrameworkVersion, Path path) {
    return TEST_EVENTS_HANDLER_FACTORY.create(component, testFramework, testFrameworkVersion, path);
  }

  public static void registerBuildEventsHandlerFactory(
      BuildEventsHandler.Factory buildEventsHandlerFactory) {
    BUILD_EVENTS_HANDLER_FACTORY = buildEventsHandlerFactory;
  }

  public static <U> BuildEventsHandler<U> createBuildEventsHandler() {
    return BUILD_EVENTS_HANDLER_FACTORY.create();
  }

  public static void registerCoverageProbeStoreFactory(
      CoverageProbeStore.Factory coverageProbeStoreFactory) {
    COVERAGE_PROBE_STORE_FACTORY = coverageProbeStoreFactory;
  }

  public static CoverageProbeStore.Factory getCoverageProbeStoreFactory() {
    return COVERAGE_PROBE_STORE_FACTORY;
  }

  public static CoverageProbeStore createCoverageProbeStore(SourcePathResolver sourcePathResolver) {
    return COVERAGE_PROBE_STORE_FACTORY.create(sourcePathResolver);
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
