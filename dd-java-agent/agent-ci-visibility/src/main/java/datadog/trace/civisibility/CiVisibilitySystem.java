package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.CITagsProvider;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.coverage.TestProbes;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.decorator.TestDecoratorImpl;
import datadog.trace.civisibility.events.BuildEventsHandlerImpl;
import datadog.trace.civisibility.events.CachingTestEventsHandlerFactory;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.RepoIndexSourcePathResolver;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  private static final String GIT_FOLDER_NAME = ".git";

  public static void start() {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    TestEventsHandler.Factory factory =
        new CachingTestEventsHandlerFactory(
            CiVisibilitySystem::createTestEventsHandler,
            config.getCiVisibilityTestEventsHandlerCacheSize());

    InstrumentationBridge.registerTestEventsHandlerFactory(factory);
    InstrumentationBridge.registerBuildEventsHandlerFactory(BuildEventsHandlerImpl::new);

    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CILocalGitInfoBuilder(GIT_FOLDER_NAME));

    CIVisibility.registerSessionFactory(buildSessionFactory(config));

    InstrumentationBridge.registerCoverageProbeStoreFactory(new TestProbes.TestProbesFactory());
  }

  private static CIVisibility.SessionFactory buildSessionFactory(Config config) {
    return (String projectName, Path projectRoot, String component, Long startTime) -> {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory();
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(projectRoot);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();

      SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot);
      Codeowners codeowners = getCodeowners(repoRoot);
      MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();
      Map<String, String> ciTags = new CITagsProvider().getCiTags(ciInfo);
      TestDecorator testDecorator = new TestDecoratorImpl(component, null, null, ciTags);

      return new DDTestSessionImpl(
          projectName,
          startTime,
          config,
          testDecorator,
          sourcePathResolver,
          codeowners,
          methodLinesResolver);
    };
  }

  private static TestEventsHandler createTestEventsHandler(
      String component, String testFramework, String testFrameworkVersion, Path path) {
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory();
    CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(path);
    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    String repoRoot = ciInfo.getCiWorkspace();
    String moduleName =
        (repoRoot != null) ? Paths.get(repoRoot).relativize(path).toString() : path.toString();

    SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot);
    Codeowners codeowners = getCodeowners(repoRoot);
    MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();
    Map<String, String> ciTags = new CITagsProvider().getCiTags(ciInfo);
    TestDecorator testDecorator =
        new TestDecoratorImpl(component, testFramework, testFrameworkVersion, ciTags);

    return new TestEventsHandlerImpl(
        moduleName,
        Config.get(),
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver);
  }

  private static String getRepositoryRoot(Path path) {
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory();
    CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(path);
    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    return ciInfo.getCiWorkspace();
  }

  private static SourcePathResolver getSourcePathResolver(String repoRoot) {
    if (repoRoot != null) {
      return new BestEfforSourcePathResolver(
          new CompilerAidedSourcePathResolver(repoRoot), new RepoIndexSourcePathResolver(repoRoot));
    } else {
      return clazz -> null;
    }
  }

  private static Codeowners getCodeowners(String repoRoot) {
    if (repoRoot != null) {
      return new CodeownersProvider().build(repoRoot);
    } else {
      return path -> null;
    }
  }
}
