package datadog.trace.civisibility;

import datadog.communication.ddagent.SharedCommunicationObjects;
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
import datadog.trace.civisibility.communication.BackendApi;
import datadog.trace.civisibility.communication.BackendApiFactory;
import datadog.trace.civisibility.coverage.TestProbes;
import datadog.trace.civisibility.events.BuildEventsHandlerImpl;
import datadog.trace.civisibility.events.CachingTestEventsHandlerFactory;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataApi;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.RepoIndexSourcePathResolver;
import datadog.trace.util.AgentThreadFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  private static final String GIT_FOLDER_NAME = ".git";

  private static final Consumer<Path> NO_OP_GIT_TREE_DATA_UPLOADER = path -> {};

  public static void start(SharedCommunicationObjects sharedCommunicationObjects) {
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
    InstrumentationBridge.registerTestDecoratorFactory(CiVisibilitySystem::createTestDecorator);

    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CILocalGitInfoBuilder(GIT_FOLDER_NAME));

    CIVisibility.registerSessionFactory(CiVisibilitySystem::createTestSession);

    InstrumentationBridge.registerCoverageProbeStoreFactory(new TestProbes.TestProbesFactory());
    InstrumentationBridge.registerGitTreeDataUploader(
        buildGitTreeDataUploader(config, sharedCommunicationObjects));
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

  private static Consumer<Path> buildGitTreeDataUploader(
      Config config, SharedCommunicationObjects sco) {
    if (!config.isCiVisibilityGitTreeDataUploadEnabled()) {
      return NO_OP_GIT_TREE_DATA_UPLOADER;
    }

    BackendApiFactory backendApiFactory = new BackendApiFactory(config, sco);
    BackendApi backendApi = backendApiFactory.createBackendApi();
    if (backendApi == null) {
      LOGGER.warn(
          "Git tree data upload will be skipped since backend API client could not be obtained");
      return NO_OP_GIT_TREE_DATA_UPLOADER;
    }

    return path -> uploadGitTreeData(config, backendApi, path);
  }

  private static void uploadGitTreeData(Config config, BackendApi backendApi, Path path) {
    String repoRoot = getRepositoryRoot(path);
    long commandTimeoutMillis = config.getCiVisibilityGitTreeCommandTimeoutMillis();
    String remoteName = config.getCiVisibilityGitRemoteName();

    GitDataApi gitDataApi = new GitDataApi(backendApi);
    GitClient gitClient = new GitClient(repoRoot, commandTimeoutMillis);
    GitDataUploader gitDataUploader = new GitDataUploader(gitDataApi, gitClient, remoteName);

    Thread gitTreeDataUploadThread =
        AgentThreadFactory.newAgentThread(
            AgentThreadFactory.AgentThread.CI_GIT_TREE_DATA_UPLOADER,
            gitDataUploader::uploadGitData);
    // we want to wait for git data upload to finish
    // even if tests finish faster
    gitTreeDataUploadThread.setDaemon(false);
    gitTreeDataUploadThread.start();

    // maven has a way of calling System.exit() when the build is done.
    // this is a hack to make it wait until git data upload has finished
    Thread gitTreeShutdownHook =
        AgentThreadFactory.newAgentThread(
            AgentThreadFactory.AgentThread.CI_GIT_TREE_SHUTDOWN_HOOK,
            () -> {
              try {
                long uploadTimeoutMillis = config.getCiVisibilityGitTreeDataUploadTimeoutMillis();
                gitTreeDataUploadThread.join(uploadTimeoutMillis);
              } catch (InterruptedException e) {
                // ignore
              }
            });
    gitTreeShutdownHook.setDaemon(false);
    Runtime.getRuntime().addShutdownHook(gitTreeShutdownHook);
  }
}
