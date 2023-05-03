package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.DDTestSession;
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
import java.util.Map;
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

    InstrumentationBridge.registerTestEventsHandlerFactory(
        CiVisibilitySystem::createTestEventsHandler);
    InstrumentationBridge.registerBuildEventsHandlerFactory(BuildEventsHandlerImpl::new);
    InstrumentationBridge.registerTestDecoratorFactory(CiVisibilitySystem::createTestDecorator);

    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CILocalGitInfoBuilder(GIT_FOLDER_NAME));

    CIVisibility.registerSessionFactory(CiVisibilitySystem::createTestSession);
  }

  private static DDTestSession createTestSession(
      String projectName, String component, Long startTime) {
    Path path = Paths.get("").toAbsolutePath();
    String repoRoot = getRepositoryRoot(path);

    SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot);
    Codeowners codeowners = getCodeowners(repoRoot);
    MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();
    TestDecorator testDecorator = createTestDecorator(component, null, null, path);

    return new DDTestSessionImpl(
        projectName,
        startTime,
        Config.get(),
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver);
  }

  private static TestEventsHandler createTestEventsHandler(
      String component, String testFramework, String testFrameworkVersion, Path path) {
    String repoRoot = getRepositoryRoot(path);
    String moduleName =
        (repoRoot != null) ? Paths.get(repoRoot).relativize(path).toString() : path.toString();

    SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot);
    Codeowners codeowners = getCodeowners(repoRoot);
    MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();
    TestDecorator testDecorator =
        createTestDecorator(component, testFramework, testFrameworkVersion, path);

    return new TestEventsHandlerImpl(
        moduleName,
        Config.get(),
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver);
  }

  private static TestDecorator createTestDecorator(
      String component, String testFramework, String testFrameworkVersion, Path path) {
    Map<String, String> ciTags = new CITagsProviderImpl().getCiTags(path);
    return new TestDecoratorImpl(component, testFramework, testFrameworkVersion, ciTags);
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
