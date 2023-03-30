package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.ci.CIInfo;
import datadog.trace.api.civisibility.ci.CIProviderInfo;
import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.source.MethodLinesResolver;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.CITagsProviderImpl;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.events.BuildEventsHandlerImpl;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.RepoIndexSourcePathResolver;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  private static final String GIT_FOLDER_NAME = ".git";

  public static void start() {
    if (!Config.get().isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    InstrumentationBridge.setCiTagsProvider(new CITagsProviderImpl());
    InstrumentationBridge.setTestEventsHandlerFactory(CiVisibilitySystem::createTestEventsHandler);
    InstrumentationBridge.setBuildEventsHandlerFactory(BuildEventsHandlerImpl::new);

    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CILocalGitInfoBuilder(GIT_FOLDER_NAME));
  }

  private static TestEventsHandler createTestEventsHandler(
      Path currentPath, TestDecorator testDecorator) {
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory();
    CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(currentPath);
    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    String repoRoot = ciInfo.getCiWorkspace();

    String moduleName;
    SourcePathResolver sourcePathResolver;
    Codeowners codeowners;
    if (repoRoot != null) {
      moduleName = Paths.get(repoRoot).relativize(currentPath).toString();
      sourcePathResolver =
          new BestEfforSourcePathResolver(
              new CompilerAidedSourcePathResolver(repoRoot),
              new RepoIndexSourcePathResolver(repoRoot));
      codeowners = new CodeownersProvider().build(repoRoot);
    } else {
      moduleName = currentPath.toString();
      sourcePathResolver = clazz -> null;
      codeowners = path -> null;
    }

    MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();

    return new TestEventsHandlerImpl(
        moduleName,
        Config.get(),
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver);
  }
}
