package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.source.MethodLinesResolver;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import java.nio.file.Path;
import java.util.Map;

public abstract class InstrumentationBridge {

  private static volatile MethodLinesResolver METHOD_LINES_RESOLVER;
  private static volatile CIProviderInfo.Factory CI_PROVIDER_INFO_FACTORY;
  private static volatile CITagsProvider CI_TAGS_PROVIDER;
  private static volatile Codeowners.Factory CODEOWNERS_FACTORY;
  private static volatile SourcePathResolver.Factory SOURCE_PATH_RESOLVER_FACTORY;
  private static volatile TestEventsHandler.Factory TEST_EVENTS_HANDLER_FACTORY;
  private static volatile BuildEventsHandler.Factory BUILD_EVENTS_HANDLER_FACTORY;

  public static MethodLinesResolver getMethodLinesResolver() {
    return METHOD_LINES_RESOLVER;
  }

  public static void setMethodLinesResolver(MethodLinesResolver methodLinesResolver) {
    METHOD_LINES_RESOLVER = methodLinesResolver;
  }

  public static void setCIProviderInfoFactory(CIProviderInfo.Factory ciProviderInfoFactory) {
    CI_PROVIDER_INFO_FACTORY = ciProviderInfoFactory;
  }

  public static CIProviderInfo getCIProviderInfo(Path currentPath) {
    return CI_PROVIDER_INFO_FACTORY.createCIProviderInfo(currentPath);
  }

  public static void setCiTagsProvider(CITagsProvider ciTagsProvider) {
    CI_TAGS_PROVIDER = ciTagsProvider;
  }

  public static Map<String, String> getCiTags(CIProviderInfo ciProviderInfo) {
    return CI_TAGS_PROVIDER.getCiTags(ciProviderInfo);
  }

  public static void setCodeownersFactory(Codeowners.Factory factory) {
    InstrumentationBridge.CODEOWNERS_FACTORY = factory;
  }

  public static Codeowners getCodeowners(String repoRoot) {
    return CODEOWNERS_FACTORY.createCodeowners(repoRoot);
  }

  public static void setSourcePathResolverFactory(SourcePathResolver.Factory factory) {
    SOURCE_PATH_RESOLVER_FACTORY = factory;
  }

  public static SourcePathResolver getSourcePathResolver(String repoRoot) {
    return SOURCE_PATH_RESOLVER_FACTORY.createSourcePathResolver(repoRoot);
  }

  public static void setTestEventsHandlerFactory(
      TestEventsHandler.Factory testEventsHandlerFactory) {
    TEST_EVENTS_HANDLER_FACTORY = testEventsHandlerFactory;
  }

  public static TestEventsHandler getTestEventsHandler(TestDecorator testDecorator) {
    return TEST_EVENTS_HANDLER_FACTORY.create(testDecorator);
  }

  public static void setBuildEventsHandlerFactory(
      BuildEventsHandler.Factory buildEventsHandlerFactory) {
    BUILD_EVENTS_HANDLER_FACTORY = buildEventsHandlerFactory;
  }

  public static <U> BuildEventsHandler<U> getBuildEventsHandler() {
    return BUILD_EVENTS_HANDLER_FACTORY.create();
  }
}
