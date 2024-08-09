package datadog.trace.civisibility;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.coverage.CoverageBridge;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.ContextStore;
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
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl;
import datadog.trace.civisibility.utils.ConcurrentHashMapContextStore;
import datadog.trace.civisibility.utils.ProcessHierarchyUtils;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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

    if (ProcessHierarchyUtils.isChild() || ProcessHierarchyUtils.isHeadless()) {
      CiVisibilityRepoServices repoServices = services.repoServices(getCurrentPath());

      ModuleExecutionSettings executionSettings =
          repoServices.moduleExecutionSettingsFactory.create(
              JvmInfo.CURRENT_JVM, repoServices.moduleName);
      if (executionSettings.isCodeCoverageEnabled()
          &&
          // Calculating coverage percentage with ITR requires
          // line-level coverage data granularity,
          // that is obtained using a different coverage mechanism
          // built on top of Jacoco
          !config.isCiVisibilityItrCodeCoveragePercentageCalculationEnabled()) {
        Predicate<String> instrumentationFilter =
            createCoverageInstrumentationFilter(config, executionSettings);
        inst.addTransformer(new CoverageClassTransformer(instrumentationFilter));
      }

      CiVisibilityCoverageServices.Child coverageServices =
          new CiVisibilityCoverageServices.Child(services, repoServices, executionSettings);
      InstrumentationBridge.registerTestEventsHandlerFactory(
          new TestEventsHandlerFactory(
              services, repoServices, coverageServices, executionSettings));
      CoverageBridge.registerCoverageStoreRegistry(coverageServices.coverageStoreFactory);
      CoverageBridge.registerGlobalCoverageStore(coverageServices.globalCoverageStore);
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
      Config config, ModuleExecutionSettings moduleExecutionSettings) {
    List<String> coverageEnabledPackages = moduleExecutionSettings.getCoverageEnabledPackages();
    int idx = 0;
    String[] includedPackages = new String[coverageEnabledPackages.size()];
    for (String coveragePackage : coverageEnabledPackages) {
      includedPackages[idx++] =
          coveragePackage
              .replace('.', '/')
              .substring(0, coveragePackage.length() - 1); // trim trailing *
    }

    String[] excludedPackages = config.getCiVisibilityCodeCoverageExcludedPackages();
    if (excludedPackages == null) {
      excludedPackages = new String[0];
    }
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

    private TestEventsHandlerFactory(
        CiVisibilityServices services,
        CiVisibilityRepoServices repoServices,
        CiVisibilityCoverageServices.Child coverageServices,
        ModuleExecutionSettings executionSettings) {
      this.services = services;
      this.repoServices = repoServices;
      if (ProcessHierarchyUtils.isChild()) {
        sessionFactory =
            childTestFrameworkSessionFactory(
                services, repoServices, coverageServices, executionSettings);
      } else {
        sessionFactory =
            headlessTestFrameworkEssionFactory(
                services, repoServices, coverageServices, executionSettings);
      }
    }

    @Override
    public <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
        String component,
        @Nullable ContextStore<SuiteKey, DDTestSuite> suiteStore,
        @Nullable ContextStore<TestKey, DDTest> testStore) {
      TestFrameworkSession testSession =
          sessionFactory.startSession(repoServices.moduleName, component, null);
      TestFrameworkModule testModule = testSession.testModuleStart(repoServices.moduleName, null);
      return new TestEventsHandlerImpl<>(
          services.metricCollector,
          testSession,
          testModule,
          suiteStore != null ? suiteStore : new ConcurrentHashMapContextStore<>(),
          testStore != null ? testStore : new ConcurrentHashMapContextStore<>());
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

      TestDecorator testDecorator = new TestDecoratorImpl(buildSystemName, repoServices.ciTags);

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
          services.methodLinesResolver,
          repoServices.moduleExecutionSettingsFactory,
          signalServer,
          repoServices.repoIndexProvider,
          coverageServices.coverageCalculatorFactory);
    };
  }

  private static TestFrameworkSession.Factory childTestFrameworkSessionFactory(
      CiVisibilityServices services,
      CiVisibilityRepoServices repoServices,
      CiVisibilityCoverageServices.Child coverageServices,
      ModuleExecutionSettings moduleExecutionSettings) {
    return (String projectName, String component, Long startTime) -> {
      long parentProcessSessionId = ProcessHierarchyUtils.getParentSessionId();
      long parentProcessModuleId = ProcessHierarchyUtils.getParentModuleId();

      TestDecorator testDecorator = new TestDecoratorImpl(component, repoServices.ciTags);
      return new ProxyTestSession(
          parentProcessSessionId,
          parentProcessModuleId,
          services.config,
          services.metricCollector,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.methodLinesResolver,
          coverageServices.coverageStoreFactory,
          coverageServices.coverageReporter,
          coverageServices.globalCoverageStore,
          services.signalClientFactory,
          moduleExecutionSettings);
    };
  }

  private static TestFrameworkSession.Factory headlessTestFrameworkEssionFactory(
      CiVisibilityServices services,
      CiVisibilityRepoServices repoServices,
      CiVisibilityCoverageServices.Child coverageServices,
      ModuleExecutionSettings moduleExecutionSettings) {
    return (String projectName, String component, Long startTime) -> {
      repoServices.gitDataUploader.startOrObserveGitDataUpload();

      TestDecorator testDecorator = new TestDecoratorImpl(component, repoServices.ciTags);
      return new HeadlessTestSession(
          projectName,
          startTime,
          repoServices.ciProvider,
          services.config,
          services.metricCollector,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.methodLinesResolver,
          coverageServices.coverageStoreFactory,
          moduleExecutionSettings);
    };
  }

  private static CIVisibility.SessionFactory manualApiSessionFactory(
      CiVisibilityServices services) {
    return (String projectName, Path projectRoot, String component, Long startTime) -> {
      CiVisibilityRepoServices repoServices = services.repoServices(projectRoot);
      TestDecorator testDecorator = new TestDecoratorImpl(component, repoServices.ciTags);
      ModuleExecutionSettings moduleExecutionSettings =
          repoServices.moduleExecutionSettingsFactory.create(JvmInfo.CURRENT_JVM, null);
      CiVisibilityCoverageServices.Child coverageServices =
          new CiVisibilityCoverageServices.Child(services, repoServices, moduleExecutionSettings);
      return new ManualApiTestSession(
          projectName,
          startTime,
          repoServices.ciProvider,
          services.config,
          services.metricCollector,
          testDecorator,
          repoServices.sourcePathResolver,
          repoServices.codeowners,
          services.methodLinesResolver,
          coverageServices.coverageStoreFactory);
    };
  }
}
