package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.bootstrap.ContextStore;
import java.util.Collection;

public abstract class InstrumentationBridge {

  private static volatile TestEventsHandler.Factory TEST_EVENTS_HANDLER_FACTORY;
  private static volatile BuildEventsHandler.Factory BUILD_EVENTS_HANDLER_FACTORY;
  private static volatile CiVisibilityMetricCollector METRIC_COLLECTOR =
      NoOpMetricCollector.INSTANCE;

  public static void registerTestEventsHandlerFactory(
      TestEventsHandler.Factory testEventsHandlerFactory) {
    TEST_EVENTS_HANDLER_FACTORY = testEventsHandlerFactory;
  }

  public static <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> createTestEventsHandler(
      String component,
      ContextStore<SuiteKey, DDTestSuite> suiteStore,
      ContextStore<TestKey, DDTest> testStore,
      Collection<LibraryCapability> capabilities) {
    return TEST_EVENTS_HANDLER_FACTORY.create(component, suiteStore, testStore, capabilities);
  }

  public static void registerBuildEventsHandlerFactory(
      BuildEventsHandler.Factory buildEventsHandlerFactory) {
    BUILD_EVENTS_HANDLER_FACTORY = buildEventsHandlerFactory;
  }

  public static <U> BuildEventsHandler<U> createBuildEventsHandler() {
    return BUILD_EVENTS_HANDLER_FACTORY.create();
  }

  public static void registerMetricCollector(CiVisibilityMetricCollector metricCollector) {
    METRIC_COLLECTOR = metricCollector;
  }

  public static CiVisibilityMetricCollector getMetricCollector() {
    return METRIC_COLLECTOR;
  }
}
