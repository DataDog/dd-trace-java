package datadog.trace.civisibility;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.debugger.DebuggerConfigBridge;
import datadog.trace.api.debugger.DebuggerConfigUpdate;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.coverage.file.instrumentation.CoverageClassTransformer;
import datadog.trace.civisibility.coverage.file.instrumentation.CoverageInstrumentationFilter;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.decorator.TestDecoratorImpl;
import datadog.trace.civisibility.domain.BuildSystemSession;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.domain.buildsystem.BuildSystemSessionImpl;
import datadog.trace.civisibility.domain.buildsystem.ProxyTestSession;
import datadog.trace.civisibility.domain.headless.HeadlessTestSession;
import datadog.trace.civisibility.domain.manualapi.ManualApiTestSession;
import datadog.trace.civisibility.events.BuildEventsHandlerImpl;
import datadog.trace.civisibility.events.NoOpTestEventsHandler;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl;
import datadog.trace.civisibility.test.ExecutionStrategy;
import datadog.trace.civisibility.utils.ConcurrentHashMapContextStore;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  public static void start(Instrumentation inst, SharedCommunicationObjects sco) {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    String injectedTracerVersion = config.getCiVisibilityInjectedTracerVersion();
    if (injectedTracerVersion != null
        && !injectedTracerVersion.equals(TracerVersion.TRACER_VERSION)) {
      throw new FatalAgentMisconfigurationError(
          "Running JVM with tracer version "
              + TracerVersion.TRACER_VERSION
              + " however parent process attempted to inject "
              + injectedTracerVersion
              + ". Do not inject the tracer into the forked JVMs manually, or ensure the manually injected version is the same as the one injected automatically");
    }

    sco.createRemaining(config);

    CiVisibilityMetricCollector metricCollector =
        config.isCiVisibilityTelemetryEnabled()
            ? new CiVisibilityMetricCollectorImpl()
            : NoOpMetricCollector.INSTANCE;
    InstrumentationBridge.registerMetricCollector(metricCollector);

    CiVisibilityServices services =
        new CiVisibilityServices(config, metricCollector, sco, GitInfoProvider.INSTANCE);

    InstrumentationBridge.registerBuildEventsHandlerFactory(buildEventsHandlerFactory(services));
    CIVisibility.registerSessionFactory(manualApiSessionFactory(services));

    if (services.processHierarchy.isChild() || services.processHierarchy.isHeadless()) {
      CiVisibilityRepoServices repoServices = services.repoServices(getCurrentPath());

      ExecutionSettings executionSettings =
          repoServices.executionSettingsFactory.create(
              JvmInfo.CURRENT_JVM, repoServices.moduleName);
      if (executionSettings.isCodeCoverageEnabled()
          &&
          // lines coverage is built on top of Jacoco,
          // so if lines are explicitly enabled,
          // we rely on Jacoco instrumentation rather than on our own coverage mechanism
          !config.isCiVisibilityCoverageLinesEnabled()) {
        Predicate<String> instrumentationFilter =
            createCoverageInstrumentationFilter(services, repoServices);
        inst.addTransformer(new CoverageClassTransformer(instrumentationFilter));
      }

      if (executionSettings.isFailedTestReplayEnabled()) {
        DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, true, null, null));
      }

      CiVisibilityCoverageServices.Child coverageServices =
          new CiVisibilityCoverageServices.Child(services, repoServices, executionSettings);
      TestEventsHandlerFactory testEventsHandlerFactory =
          new TestEventsHandlerFactory(services, repoServices, coverageServices, executionSettings);
      InstrumentationBridge.registerTestEventsHandlerFactory(testEventsHandlerFactory);
      CoveragePerTestBridge.registerCoverageStoreRegistry(coverageServices.coverageStoreFactory);

      AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
      tracerAPI.addShutdownListener(testEventsHandlerFactory::shutdown);
    } else {
      InstrumentationBridge.registerTestEventsHandlerFactory(new NoOpTestEventsHandler.Factory());
    }
  }

  private static Path getCurrentPath() {
    Path currentPath = Paths.get("").toAbsolutePath();
    try {
      return currentPath.toRealPath();
    } catch (Exception e) {
      return currentPath;
    }
  }

  private static Predicate<String> createCoverageInstrumentationFilter(
      CiVisibilityServices services, CiVisibilityRepoServices repoServices) {
    String[] includedPackages = services.config.getCiVisibilityCodeCoverageIncludedPackages();
    if (includedPackages.length == 0 && services.processHierarchy.isHeadless()) {
      RepoIndex repoIndex = repoServices.repoIndexProvider.getIndex();
      includedPackages =
          Config.convertJacocoExclusionFormatToPackagePrefixes(repoIndex.getRootPackages());
    }
    String[] excludedPackages = services.config.getCiVisibilityCodeCoverageExcludedPackages();
    return new CoverageInstrumentationFilter(includedPackages, excludedPackages);
  }

  private static BuildEventsHandler.Factory buildEventsHandlerFactory(
      CiVisibilityServices services) {
    BuildSystemSession.Factory sessionFactory = buildSystemSessionFactory(services);
    return new BuildEventsHandler.Factory() {
      @Override
      public <U> BuildEventsHandler<U> create() {
        return new BuildEventsHandlerImpl<>(sessionFactory, services.jvmInfoFactory);
      }
    };
  }

  private static final class TestEventsHandlerFactory implements TestEventsHandler.Factory {
    private final CiVisibilityServices services;
    private final CiVisibilityRepoServices repoServices;
    private final TestFrameworkSession.Factory sessionFactory;

    private final Collection<TestEventsHandler<?, ?>> handlers = new CopyOnWriteArrayList<>();

    private TestEventsHandlerFactory(
        CiVisibilityServices services,
        CiVisibilityRepoServices repoServices,
        CiVisibilityCoverageServices.Child coverageServices,
        ExecutionSettings executionSettings) {
      this.services = services;
      this.repoServices = repoServices;
      if (services.processHierarchy.isChild()) {
        sessionFactory =
            childTestFrameworkSessionFactory(
                services, repoServices, coverageServices, executionSettings);
      } else {
        sessionFactory =
            headlessTestFrameworkSessionFactory(
                services, repoServices, coverageServices, executionSettings);
      }
    }

    @Override
    public <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
        String component,
        @Nullable ContextStore<SuiteKey, DDTestSuite> suiteStore,
        @Nullable ContextStore<TestKey, DDTest> testStore,
        Collection<LibraryCapability> capabilities) {
      TestFrameworkSession testSession =
          sessionFactory.startSession(repoServices.moduleName, component, null, capabilities);
      TestFrameworkModule testModule = testSession.testModuleStart(repoServices.moduleName, null);
      TestEventsHandlerImpl<SuiteKey, TestKey> handler =
          new TestEventsHandlerImpl<>(
              services.metricCollector,
              testSession,
              testModule,
              suiteStore != null ? suiteStore : new ConcurrentHashMapContextStore<>(),
              testStore != null ? testStore : new ConcurrentHashMapContextStore<>());
      handlers.add(handler);
      return handler;
    }

    public void shutdown() {
      for (TestEventsHandler<?, ?> handler : handlers) {
        handler.close();
      }
    }
  }

  private static BuildSystemSession.Factory buildSystemSessionFactory(
      CiVisibilityServices services) {
    return (String projectName,
        Path projectRoot,
        String startCommand,
        String buildSystemName,
        Long startTime) -> {
      CiVisibilityRepoServices repoServices = services.repoServices(projectRoot);

      // Session needs to see the most recent commit in a repo.
      // Cache shouldn't be a problem normally,
      // but it can get stale if we're inside a long-running Gradle daemon
      // and repo that we're using gets updated
      services.gitInfoProvider.invalidateCache();

      repoServices.gitDataUploader.startOrObserveGitDataUpload();

      String sessionName = services.config.getCiVisibilitySessionName();
      TestDecorator testDecorator =
          new TestDecoratorImpl(buildSystemName, sessionName, startCommand, repoServices.ciTags);

      String signalServerHost = services.config.getCiVisibilitySignalServerHost();
      int signalServerPort = services.config.getCiVisibilitySignalServerPort();
      SignalServer signalServer = new SignalServer(signalServerHost, signalServerPort);

      CiVisibilityCoverageServices.Parent coverageServices =
          new CiVisibilityCoverageServices.Parent(services, repoServices);
      return new BuildSystemSessionImpl<>(
          projectName,
          startCommand,
          startTime,
          repoServices.ciProvider,
          services.config,
          services.metricCollector,
          coverageServices.moduleSignalRouter,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.linesResolver,
          repoServices.executionSettingsFactory,
          signalServer,
          repoServices.repoIndexProvider,
          coverageServices.coverageProcessorFactory);
    };
  }

  private static TestFrameworkSession.Factory childTestFrameworkSessionFactory(
      CiVisibilityServices services,
      CiVisibilityRepoServices repoServices,
      CiVisibilityCoverageServices.Child coverageServices,
      ExecutionSettings executionSettings) {
    return (String projectName,
        String component,
        Long startTime,
        Collection<LibraryCapability> capabilities) -> {
      String sessionName = services.config.getCiVisibilitySessionName();
      String testCommand = services.config.getCiVisibilityTestCommand();
      TestDecorator testDecorator =
          new TestDecoratorImpl(component, sessionName, testCommand, repoServices.ciTags);

      ExecutionStrategy executionStrategy =
          new ExecutionStrategy(
              services.config,
              executionSettings,
              repoServices.sourcePathResolver,
              services.linesResolver);

      // only add report upload capability for children sessions,
      // because report upload is only supported when the build system is instrumented
      capabilities = new ArrayList<>(capabilities);
      capabilities.add(LibraryCapability.COV_REPORT_UPLOAD);

      return new ProxyTestSession(
          services.processHierarchy.parentProcessModuleContext,
          services.config,
          services.metricCollector,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.linesResolver,
          coverageServices.coverageStoreFactory,
          coverageServices.coverageReporter,
          services.signalClientFactory,
          executionStrategy,
          capabilities);
    };
  }

  private static TestFrameworkSession.Factory headlessTestFrameworkSessionFactory(
      CiVisibilityServices services,
      CiVisibilityRepoServices repoServices,
      CiVisibilityCoverageServices.Child coverageServices,
      ExecutionSettings executionSettings) {
    return (String projectName,
        String component,
        Long startTime,
        Collection<LibraryCapability> capabilities) -> {
      repoServices.gitDataUploader.startOrObserveGitDataUpload();

      String sessionName = services.config.getCiVisibilitySessionName();
      TestDecorator testDecorator =
          new TestDecoratorImpl(component, sessionName, projectName, repoServices.ciTags);

      ExecutionStrategy executionStrategy =
          new ExecutionStrategy(
              services.config,
              executionSettings,
              repoServices.sourcePathResolver,
              services.linesResolver);
      return new HeadlessTestSession(
          projectName,
          startTime,
          repoServices.ciProvider,
          services.config,
          services.metricCollector,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.linesResolver,
          coverageServices.coverageStoreFactory,
          executionStrategy,
          capabilities);
    };
  }

  private static CIVisibility.SessionFactory manualApiSessionFactory(
      CiVisibilityServices services) {
    return (String projectName, Path projectRoot, String component, Long startTime) -> {
      CiVisibilityRepoServices repoServices = services.repoServices(projectRoot);

      String sessionName = services.config.getCiVisibilitySessionName();
      TestDecorator testDecorator =
          new TestDecoratorImpl(component, sessionName, projectName, repoServices.ciTags);

      ExecutionSettings executionSettings =
          repoServices.executionSettingsFactory.create(JvmInfo.CURRENT_JVM, null);
      CiVisibilityCoverageServices.Child coverageServices =
          new CiVisibilityCoverageServices.Child(services, repoServices, executionSettings);
      return new ManualApiTestSession(
          projectName,
          startTime,
          repoServices.ciProvider,
          services.config,
          services.metricCollector,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.linesResolver,
          coverageServices.coverageStoreFactory);
    };
  }
}
