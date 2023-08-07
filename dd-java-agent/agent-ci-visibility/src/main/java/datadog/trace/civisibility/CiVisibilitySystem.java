package datadog.trace.civisibility;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
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
import datadog.trace.civisibility.coverage.NoopCoverageProbeStore;
import datadog.trace.civisibility.coverage.SegmentlessTestProbes;
import datadog.trace.civisibility.coverage.TestProbes;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.decorator.TestDecoratorImpl;
import datadog.trace.civisibility.events.BuildEventsHandlerImpl;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.git.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.GitDataApi;
import datadog.trace.civisibility.git.tree.GitDataUploader;
import datadog.trace.civisibility.git.tree.GitDataUploaderImpl;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.index.RepoIndexBuilder;
import datadog.trace.civisibility.source.index.RepoIndexFetcher;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.source.index.RepoIndexSourcePathResolver;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
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

    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CIProviderGitInfoBuilder());
    GitInfoProvider.INSTANCE.registerGitInfoBuilder(new CILocalGitInfoBuilder(GIT_FOLDER_NAME));

    DDTestSessionImpl.SessionImplFactory sessionFactory = sessionFactory(config, sco);
    CIVisibility.registerSessionFactory(sessionFactory);

    InstrumentationBridge.registerTestEventsHandlerFactory(
        testEventsHandlerFactory(config, sessionFactory));
    InstrumentationBridge.registerBuildEventsHandlerFactory(
        buildEventsHandlerFactory(sessionFactory));
    InstrumentationBridge.registerCoverageProbeStoreFactory(buildTestProbesFactory(config));
  }

  private static CoverageProbeStore.Factory buildTestProbesFactory(Config config) {
    if (!config.isCiVisibilityCodeCoverageEnabled()) {
      return new NoopCoverageProbeStore.NoopCoverageProbeStoreFactory();
    }
    if (!config.isCiVisibilityCoverageSegmentsEnabled()) {
      return new SegmentlessTestProbes.SegmentlessTestProbesFactory();
    }
    return new TestProbes.TestProbesFactory();
  }

  private static DDTestSessionImpl.SessionImplFactory sessionFactory(
      Config config, SharedCommunicationObjects sco) {
    BackendApiFactory backendApiFactory = new BackendApiFactory(config, sco);
    BackendApi backendApi = backendApiFactory.createBackendApi();

    return (String projectName, Path projectRoot, String component, Long startTime) -> {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config);
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(projectRoot);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();

      RepoIndexProvider indexProvider = getRepoIndexProvider(config, repoRoot);
      SourcePathResolver sourcePathResolver = getSourcePathResolver(repoRoot, indexProvider);
      Codeowners codeowners = getCodeowners(repoRoot);
      MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();
      Map<String, String> ciTags = new CITagsProvider().getCiTags(ciInfo);
      TestDecorator testDecorator = new TestDecoratorImpl(component, ciTags);
      TestModuleRegistry testModuleRegistry = new TestModuleRegistry();

      GitDataUploader gitDataUploader = buildGitDataUploader(config, backendApi, repoRoot);
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory =
          buildModuleExecutionSettingsFactory(config, backendApi, gitDataUploader, repoRoot);

      String signalServerHost = config.getCiVisibilitySignalServerHost();
      int signalServerPort = config.getCiVisibilitySignalServerPort();
      SignalServer signalServer = new SignalServer(signalServerHost, signalServerPort);

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
        // only start Git data upload in parent process
        gitDataUploader.startOrObserveGitDataUpload();

        RepoIndexBuilder indexBuilder = new RepoIndexBuilder(repoRoot, FileSystems.getDefault());
        return new DDTestSessionParent(
            projectName,
            startTime,
            config,
            testModuleRegistry,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            moduleExecutionSettingsFactory,
            signalServer,
            indexBuilder);
      }

      InetSocketAddress signalServerAddress = null;
      String host =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(
                  CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST));
      String port =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(
                  CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT));
      if (host != null && port != null) {
        signalServerAddress = new InetSocketAddress(host, Integer.parseInt(port));
      }

      return new DDTestSessionChild(
          parentProcessSessionId,
          parentProcessModuleId,
          config,
          testDecorator,
          sourcePathResolver,
          codeowners,
          methodLinesResolver,
          signalServerAddress);
    };
  }

  private static GitDataUploader buildGitDataUploader(
      Config config, BackendApi backendApi, String repoRoot) {
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

    long commandTimeoutMillis = config.getCiVisibilityGitCommandTimeoutMillis();
    String remoteName = config.getCiVisibilityGitRemoteName();

    GitDataApi gitDataApi = new GitDataApi(backendApi);
    GitClient gitClient = new GitClient(repoRoot, "1 month ago", 1000, commandTimeoutMillis);
    return new GitDataUploaderImpl(config, gitDataApi, gitClient, remoteName);
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

  private static RepoIndexProvider getRepoIndexProvider(Config config, String repoRoot) {
    String host = config.getCiVisibilitySignalServerHost();
    int port = config.getCiVisibilitySignalServerPort();
    if (host != null && port > 0 && config.isCiVisibilityRepoIndexSharingEnabled()) {
      InetSocketAddress serverAddress = new InetSocketAddress(host, port);
      Supplier<SignalClient> signalClientFactory =
          () -> {
            try {
              return new SignalClient(serverAddress);
            } catch (IOException e) {
              throw new RuntimeException(
                  "Could not instantiate signal client. " + "Host: " + host + ", port: " + port, e);
            }
          };
      return new RepoIndexFetcher(signalClientFactory);
    } else {
      return new RepoIndexBuilder(repoRoot, FileSystems.getDefault());
    }
  }

  private static SourcePathResolver getSourcePathResolver(
      String repoRoot, RepoIndexProvider indexProvider) {
    if (repoRoot != null) {
      RepoIndexSourcePathResolver indexSourcePathResolver =
          new RepoIndexSourcePathResolver(repoRoot, indexProvider);
      return new BestEfforSourcePathResolver(
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

  private static TestEventsHandler.Factory testEventsHandlerFactory(
      Config config, DDTestSessionImpl.SessionImplFactory sessionFactory) {
    return (String component, Path path) -> {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config);
      CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(path);
      CIInfo ciInfo = ciProviderInfo.buildCIInfo();
      String repoRoot = ciInfo.getCiWorkspace();
      String moduleName =
          (repoRoot != null) ? Paths.get(repoRoot).relativize(path).toString() : path.toString();

      DDTestSessionImpl testSession =
          sessionFactory.startSession(moduleName, path, component, null);
      DDTestModuleImpl testModule = testSession.testModuleStart(moduleName, null);
      return new TestEventsHandlerImpl(testSession, testModule);
    };
  }

  private static BuildEventsHandler.Factory buildEventsHandlerFactory(
      DDTestSessionImpl.SessionImplFactory sessionFactory) {
    return new BuildEventsHandler.Factory() {
      @Override
      public <U> BuildEventsHandler<U> create() {
        return new BuildEventsHandlerImpl<>(sessionFactory, new JvmInfoFactory());
      }
    };
  }
}
