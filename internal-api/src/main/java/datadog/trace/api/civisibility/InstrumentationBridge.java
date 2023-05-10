package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.nio.file.Path;

public abstract class InstrumentationBridge {

  private static volatile TestDecorator.Factory TEST_DECORATOR_FACTORY;
  private static volatile TestEventsHandler.Factory TEST_EVENTS_HANDLER_FACTORY;
  private static volatile BuildEventsHandler.Factory BUILD_EVENTS_HANDLER_FACTORY;

  public static void registerTestDecoratorFactory(TestDecorator.Factory testDecoratorFactory) {
    TEST_DECORATOR_FACTORY = testDecoratorFactory;
  }

  public static TestDecorator createTestDecorator(
      String component, String testFramework, String testFrameworkVersion, Path path) {
    return TEST_DECORATOR_FACTORY.create(component, testFramework, testFrameworkVersion, path);
  }

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
}
