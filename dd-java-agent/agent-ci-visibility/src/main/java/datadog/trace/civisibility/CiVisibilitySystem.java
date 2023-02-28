package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.RepoIndexSourcePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  public static void start() {
    final Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    InstrumentationBridge.setCIProviderInfoFactory(CIProviderInfoFactory::createCIProviderInfo);
    InstrumentationBridge.setCiTagsProvider(new CITagsProviderImpl());
    InstrumentationBridge.setMethodLinesResolver(new MethodLinesResolverImpl());

    CodeownersProvider codeownersProvider = new CodeownersProvider();
    Codeowners emptyCodeowners = path -> null;
    InstrumentationBridge.setCodeownersFactory(
        repoRoot -> repoRoot != null ? codeownersProvider.build(repoRoot) : emptyCodeowners);

    SourcePathResolver emptySourcePathResolver = clazz -> null;
    InstrumentationBridge.setSourcePathResolverFactory(
        repoRoot ->
            repoRoot != null
                ? new BestEfforSourcePathResolver(
                    new CompilerAidedSourcePathResolver(repoRoot),
                    new RepoIndexSourcePathResolver(repoRoot))
                : emptySourcePathResolver);
  }
}
