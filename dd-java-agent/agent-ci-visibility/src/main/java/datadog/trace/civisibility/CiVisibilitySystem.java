package datadog.trace.civisibility;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.CITagsProvider;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.communication.BackendApi;
import datadog.trace.civisibility.communication.BackendApiFactory;
import datadog.trace.civisibility.config.CachingModuleExecutionSettingsFactory;
import datadog.trace.civisibility.config.ConfigurationApi;
import datadog.trace.civisibility.config.ConfigurationApiImpl;
import datadog.trace.civisibility.config.JvmInfoFactory;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactoryImpl;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.coverage.NoopCoverageProbeStore;
import datadog.trace.civisibility.coverage.SegmentlessTestProbes;
import datadog.trace.civisibility.coverage.TestProbes;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.decorator.TestDecoratorImpl;
import datadog.trace.civisibility.events.BuildEventsHandlerImpl;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.git.GitClientGitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataApi;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.git.tree.GitDataUploaderImpl;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.source.BestEffortMethodLinesResolver;
import datadog.trace.civisibility.source.BestEffortSourcePathResolver;
import datadog.trace.civisibility.source.ByteCodeMethodLinesResolver;
import datadog.trace.civisibility.source.CompilerAidedMethodLinesResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndexBuilder;
import datadog.trace.civisibility.source.index.RepoIndexFetcher;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.source.index.RepoIndexSourcePathResolver;
import datadog.trace.util.Strings;
import java.net.InetSocketAddress;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  private static final String GIT_FOLDER_NAME = ".git";

  public static void start(SharedCommunicationObjects sco) {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    GitClient.Factory gitClientFactory = buildGitClientFactory(config);
    CoverageProbeStoreFactory coverageProbeStoreFactory = buildTestProbesFactory(config);

    GitInfoProvider gitInfoProvider = GitInfoProvider.INSTANCE;
    gitInfoProvider.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    gitInfoProvider.registerGitInfoBuilder(
        new CILocalGitInfoBuilder(gitClientFactory, GIT_FOLDER_NAME));
    gitInfoProvider.registerGitInfoBuilder(new GitClientGitInfoBuilder(config, gitClientFactory));

    InstrumentationBridge.registerBuildEventsHandlerFactory(
        buildEventsHandlerFactory(
            config, sco, gitInfoProvider, coverageProbeStoreFactory, gitClientFactory));
    InstrumentationBridge.registerTestEventsHandlerFactory(
        testEventsHandlerFactory(
            config, sco, gitInfoProvider, coverageProbeStoreFactory, gitClientFactory));
    InstrumentationBridge.registerCoverageProbeStoreRegistry(coverageProbeStoreFactory);

    CIVisibility.registerSessionFactory(apiSessionFactory(config, coverageProbeStoreFactory));
  }

  private static GitClient.Factory buildGitClientFactory(Config config) {
    return new GitClient.Factory(config);
  }

  private static CoverageProbeStoreFactory buildTestProbesFactory(Config config) {
    if (!config.isCiVisibilityCodeCoverageEnabled()) {
      return new NoopCoverageProbeStore.NoopCoverageProbeStoreFactory();
    }
    if (!config.isCiVisibilityCoverageSegmentsEnabled()) {
      return new SegmentlessTestProbes.SegmentlessTestProbesFactory();
    }
    return new TestProbes.TestProbesFactory();
  }

  private static BuildEventsHandler.Factory buildEventsHandlerFactory(
      Config config,
      SharedCommunicationObjects sco,
      GitInfoProvider gitInfoProvider,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      GitClient.Factory gitClientFactory) {
    DDBuildSystemSession.Factory sessionFactory =
        buildSystemSessionFactory(
            config, sco, gitInfoProvider, coverageProbeStoreFactory, gitClientFactory);
    return new BuildEventsHandler.Factory() {
      @Override
      public <U> BuildEventsHandler<U> create() {
        return new BuildEventsHandlerImpl<>(sessionFactory, new JvmInfoFactory());
      }
    };
  }

  private static DDBuildSystemSession.Factory buildSystemSessionFactory(
      Config config,
      SharedCommunicationObjects sco,
      GitInfoProvider gitInfoProvider,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      GitClient.Factory gitClientFactory) {
    BackendApiFactory backendApiFactory = new BackendApiFactory(config, sco);
    BackendApi backendApi = backendApiFactory.createBackendApi();

    return (String projectName,
        Path projectRoot,
        String startCommand,
        String buildSystemName,
        Long startTime) -> {
      // Session needs to see the most recent commit in a repo.
      // Cache shouldn't be a problem normally,
      // but it can get stale if we're inside a long-running Gradle daemon
      // and repo that we're using gets updated
      gitInfoProvider.invalidateCache();

      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config);
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(projectRoot);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();

      RepoIndexProvider indexProvider = new RepoIndexBuilder(repoRoot, FileSystems.getDefault());
      SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot, indexProvider);
      Codeowners codeowners = getCodeowners(repoRoot);

      MethodLinesResolver methodLinesResolver =
          new BestEffortMethodLinesResolver(
              new CompilerAidedMethodLinesResolver(), new ByteCodeMethodLinesResolver());

      Map<String, String> ciTags = new CITagsProvider().getCiTags(ciInfo);
      TestDecorator testDecorator = new TestDecoratorImpl(buildSystemName, ciTags);
      TestModuleRegistry testModuleRegistry = new TestModuleRegistry();

      GitDataUploader gitDataUploader =
          buildGitDataUploader(config, gitInfoProvider, gitClientFactory, backendApi, repoRoot);
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory =
          buildModuleExecutionSettingsFactory(config, backendApi, gitDataUploader, repoRoot);

      String signalServerHost = config.getCiVisibilitySignalServerHost();
      int signalServerPort = config.getCiVisibilitySignalServerPort();
      SignalServer signalServer = new SignalServer(signalServerHost, signalServerPort);

      // only start Git data upload in parent process
      gitDataUploader.startOrObserveGitDataUpload();

      RepoIndexBuilder indexBuilder = new RepoIndexBuilder(repoRoot, FileSystems.getDefault());
      return new DDBuildSystemSessionImpl(
          projectName,
          repoRoot,
          startCommand,
          startTime,
          config,
          testModuleRegistry,
          testDecorator,
          sourcePathResolver,
          codeowners,
          methodLinesResolver,
          moduleExecutionSettingsFactory,
          coverageProbeStoreFactory,
          signalServer,
          indexBuilder);
    };
  }

  private static TestEventsHandler.Factory testEventsHandlerFactory(
      Config config,
      SharedCommunicationObjects sco,
      GitInfoProvider gitInfoProvider,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      GitClient.Factory gitClientFactory) {
    DDTestFrameworkSession.Factory sessionFactory =
        testFrameworkSessionFactory(
            config, sco, gitInfoProvider, coverageProbeStoreFactory, gitClientFactory);
    return (String component, Path path) -> {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config);
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(path);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();
      String moduleName =
          (repoRoot != null) ? Paths.get(repoRoot).relativize(path).toString() : path.toString();

      DDTestFrameworkSession testSession =
          sessionFactory.startSession(moduleName, path, component, null);
      DDTestFrameworkModule testModule = testSession.testModuleStart(moduleName, null);
      return new TestEventsHandlerImpl(testSession, testModule);
    };
  }

  private static DDTestFrameworkSession.Factory testFrameworkSessionFactory(
      Config config,
      SharedCommunicationObjects sco,
      GitInfoProvider gitInfoProvider,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      GitClient.Factory gitClientFactory) {
    BackendApiFactory backendApiFactory = new BackendApiFactory(config, sco);
    BackendApi backendApi = backendApiFactory.createBackendApi();

    return (String projectName, Path projectRoot, String component, Long startTime) -> {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config);
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(projectRoot);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();

      Codeowners codeowners = getCodeowners(repoRoot);
      MethodLinesResolver methodLinesResolver =
          new BestEffortMethodLinesResolver(
              new CompilerAidedMethodLinesResolver(), new ByteCodeMethodLinesResolver());

      Map<String, String> ciTags = new CITagsProvider().getCiTags(ciInfo);
      TestDecorator testDecorator = new TestDecoratorImpl(component, ciTags);

      // fallbacks to System.getProperty below are needed for cases when
      // system variables are set after config was initialized
      Long parentProcessSessionId = config.getCiVisibilitySessionId();
      if (parentProcessSessionId == null) {
        String systemProp =
            System.getProperty(
                Strings.propertyNameToSystemPropertyName(
                    CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
        if (systemProp != null) {
          parentProcessSessionId = Long.parseLong(systemProp);
        }
      }

      Long parentProcessModuleId = config.getCiVisibilityModuleId();
      if (parentProcessModuleId == null) {
        String systemProp =
            System.getProperty(
                Strings.propertyNameToSystemPropertyName(
                    CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
        if (systemProp != null) {
          parentProcessModuleId = Long.parseLong(systemProp);
        }
      }

      // session ID and module ID are supplied by the parent process
      // if it runs with the tracer attached;

      // if session ID and module ID are not provided,
      // either we are in the build system
      // or we are in the tests JVM and the build system is not instrumented
      if (parentProcessSessionId == null || parentProcessModuleId == null) {
        GitDataUploader gitDataUploader =
            buildGitDataUploader(config, gitInfoProvider, gitClientFactory, backendApi, repoRoot);
        ModuleExecutionSettingsFactory moduleExecutionSettingsFactory =
            buildModuleExecutionSettingsFactory(config, backendApi, gitDataUploader, repoRoot);
        RepoIndexProvider indexProvider = new RepoIndexBuilder(repoRoot, FileSystems.getDefault());
        SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot, indexProvider);

        // only start Git data upload in parent process
        gitDataUploader.startOrObserveGitDataUpload();

        return new DDTestFrameworkSessionImpl(
            projectName,
            startTime,
            config,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            coverageProbeStoreFactory,
            moduleExecutionSettingsFactory);
      }

      InetSocketAddress signalServerAddress = getSignalServerAddress();
      SignalClient.Factory signalClientFactory = new SignalClient.Factory(signalServerAddress);
      RepoIndexProvider indexProvider = new RepoIndexFetcher(signalClientFactory);
      SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot, indexProvider);
      CoverageDataSupplier coverageDataSupplier = InstrumentationBridge::getCoverageData;
      return new DDTestFrameworkSessionProxy(
          parentProcessSessionId,
          parentProcessModuleId,
          config,
          testDecorator,
          sourcePathResolver,
          codeowners,
          methodLinesResolver,
          coverageProbeStoreFactory,
          coverageDataSupplier,
          signalServerAddress);
    };
  }

  private static InetSocketAddress getSignalServerAddress() {
    String host =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST));
    String port =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT));
    if (host != null && port != null) {
      return new InetSocketAddress(host, Integer.parseInt(port));
    } else {
      return null;
    }
  }

  private static CIVisibility.SessionFactory apiSessionFactory(
      Config config, CoverageProbeStoreFactory coverageProbeStoreFactory) {
    return (String projectName, Path projectRoot, String component, Long startTime) -> {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config);
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(projectRoot);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();

      Codeowners codeowners = getCodeowners(repoRoot);
      MethodLinesResolver methodLinesResolver =
          new BestEffortMethodLinesResolver(
              new CompilerAidedMethodLinesResolver(), new ByteCodeMethodLinesResolver());

      Map<String, String> ciTags = new CITagsProvider().getCiTags(ciInfo);
      TestDecorator testDecorator = new TestDecoratorImpl(component, ciTags);
      RepoIndexProvider indexProvider = new RepoIndexBuilder(repoRoot, FileSystems.getDefault());
      SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot, indexProvider);

      return new DDTestSessionImpl(
          projectName,
          startTime,
          config,
          testDecorator,
          sourcePathResolver,
          codeowners,
          methodLinesResolver,
          coverageProbeStoreFactory);
    };
  }

  private static GitDataUploader buildGitDataUploader(
      Config config,
      GitInfoProvider gitInfoProvider,
      GitClient.Factory gitClientFactory,
      BackendApi backendApi,
      String repoRoot) {
    if (!config.isCiVisibilityGitUploadEnabled()) {
      return () -> CompletableFuture.completedFuture(null);
    }

    if (backendApi == null) {
      LOGGER.warn(
          "Git tree data upload will be skipped since backend API client could not be created");
      return () -> CompletableFuture.completedFuture(null);
    }

    if (repoRoot == null) {
      LOGGER.warn(
          "Git tree data upload will be skipped since Git repository path could not be determined");
      return () -> CompletableFuture.completedFuture(null);
    }

    String remoteName = config.getCiVisibilityGitRemoteName();
    GitDataApi gitDataApi = new GitDataApi(backendApi);
    GitClient gitClient = gitClientFactory.create(repoRoot);
    return new GitDataUploaderImpl(
        config, gitDataApi, gitClient, gitInfoProvider, repoRoot, remoteName);
  }

  private static ModuleExecutionSettingsFactory buildModuleExecutionSettingsFactory(
      Config config,
      BackendApi backendApi,
      GitDataUploader gitDataUploader,
      String repositoryRoot) {
    ConfigurationApi configurationApi;
    if (backendApi == null) {
      LOGGER.warn(
          "Remote config and skippable tests requests will be skipped since backend API client could not be created");
      configurationApi = ConfigurationApi.NO_OP;
    } else {
      configurationApi = new ConfigurationApiImpl(backendApi);
    }
    return new CachingModuleExecutionSettingsFactory(
        config,
        new ModuleExecutionSettingsFactoryImpl(
            config, configurationApi, gitDataUploader, repositoryRoot));
  }

  private static SourcePathResolver getSourcePathResolver(
      String repoRoot, RepoIndexProvider indexProvider) {
    if (repoRoot != null) {
      RepoIndexSourcePathResolver indexSourcePathResolver =
          new RepoIndexSourcePathResolver(repoRoot, indexProvider);
      return new BestEffortSourcePathResolver(
          new CompilerAidedSourcePathResolver(repoRoot), indexSourcePathResolver);
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
