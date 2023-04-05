package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.events.impl.BuildEventsHandlerImpl;
import datadog.trace.api.civisibility.events.impl.TestEventsHandlerImpl;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.coverage.TestProbes;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.RepoIndexSourcePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  private static final String GIT_FOLDER_NAME = ".git";

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

    InstrumentationBridge.setTestEventsHandlerFactory(TestEventsHandlerImpl::new);
    InstrumentationBridge.setBuildEventsHandlerFactory(BuildEventsHandlerImpl::new);

    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CILocalGitInfoBuilder(GIT_FOLDER_NAME));

    InstrumentationBridge.setCoverageProbeStoreFactory(new TestProbes.TestProbesFactory());
  }
}
