package datadog.trace.api.ci;

import datadog.trace.bootstrap.instrumentation.ci.source.MethodLinesResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.SourcePathResolver;

public abstract class InstrumentationBridge {

  private static volatile MethodLinesResolver.Factory METHOD_LINES_RESOLVER_FACTORY;
  private static volatile SourcePathResolver.Factory SOURCE_PATH_RESOLVER_FACTORY;

  public static MethodLinesResolver createMethodLinesResolver() {
    return METHOD_LINES_RESOLVER_FACTORY.create();
  }

  public static void setMethodLinesResolverFactory(MethodLinesResolver.Factory factory) {
    METHOD_LINES_RESOLVER_FACTORY = factory;
  }

  public static SourcePathResolver createSourcePathResolver(String repoRoot) {
    return SOURCE_PATH_RESOLVER_FACTORY.create(repoRoot);
  }

  public static void setSourcePathResolverFactory(SourcePathResolver.Factory factory) {
    SOURCE_PATH_RESOLVER_FACTORY = factory;
  }
}
