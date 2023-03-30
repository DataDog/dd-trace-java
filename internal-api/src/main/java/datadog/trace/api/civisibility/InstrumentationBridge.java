package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.ci.CITagsProvider;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.nio.file.Path;
import java.util.Map;

public abstract class InstrumentationBridge {

  private static volatile CITagsProvider CI_TAGS_PROVIDER;
  private static volatile TestEventsHandler.Factory TEST_EVENTS_HANDLER_FACTORY;
  private static volatile BuildEventsHandler.Factory BUILD_EVENTS_HANDLER_FACTORY;

  public static void setCiTagsProvider(CITagsProvider ciTagsProvider) {
    CI_TAGS_PROVIDER = ciTagsProvider;
  }

  public static Map<String, String> getCiTags(Path path) {
    return CI_TAGS_PROVIDER.getCiTags(path);
  }

  public static void setTestEventsHandlerFactory(
      TestEventsHandler.Factory testEventsHandlerFactory) {
    TEST_EVENTS_HANDLER_FACTORY = testEventsHandlerFactory;
  }

  public static TestEventsHandler getTestEventsHandler(Path currentPath, TestDecorator decorator) {
    return TEST_EVENTS_HANDLER_FACTORY.create(currentPath, decorator);
  }

  public static void setBuildEventsHandlerFactory(
      BuildEventsHandler.Factory buildEventsHandlerFactory) {
    BUILD_EVENTS_HANDLER_FACTORY = buildEventsHandlerFactory;
  }

  public static <U> BuildEventsHandler<U> getBuildEventsHandler() {
    return BUILD_EVENTS_HANDLER_FACTORY.create();
  }
}
