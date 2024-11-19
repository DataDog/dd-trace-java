package datadog.trace.civisibility.domain.buildsystem;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;

import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.coverage.percentage.CoverageCalculator;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.BuildSystemModule;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import datadog.trace.util.Strings;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class BuildSystemModuleImpl extends AbstractTestModule implements BuildSystemModule {

  private final CoverageCalculator coverageCalculator;
  private final ModuleSignalRouter moduleSignalRouter;
  private final BuildModuleSettings settings;

  private final LongAdder testsSkipped = new LongAdder();

  private volatile boolean codeCoverageEnabled;
  private volatile boolean testSkippingEnabled;

  public <T extends CoverageCalculator> BuildSystemModuleImpl(
      AgentSpan.Context sessionSpanContext,
      String moduleName,
      String startCommand,
      @Nullable Long startTime,
      InetSocketAddress signalServerAddress,
      BuildModuleLayout moduleLayout,
      @Nullable Collection<Path> classpath,
      @Nullable JavaAgent jacocoAgent,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      ModuleSignalRouter moduleSignalRouter,
      CoverageCalculator.Factory<T> coverageCalculatorFactory,
      T sessionCoverageCalculator,
      ExecutionSettings executionSettings,
      BuildSessionSettings sessionSettings,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        moduleName,
        startTime,
        InstrumentationType.BUILD,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        onSpanFinish);
    this.coverageCalculator =
        coverageCalculatorFactory.moduleCoverage(
            span.getSpanId(), moduleLayout, executionSettings, sessionCoverageCalculator);
    this.moduleSignalRouter = moduleSignalRouter;

    moduleSignalRouter.registerModuleHandler(
        span.getSpanId(),
        SignalType.MODULE_EXECUTION_RESULT,
        this::onModuleExecutionResultReceived);

    settings =
        new BuildModuleSettings(
            getPropertiesPropagatedToChildProcess(
                moduleName,
                startCommand,
                classpath,
                jacocoAgent,
                signalServerAddress,
                executionSettings,
                sessionSettings));

    setTag(Tags.TEST_COMMAND, startCommand);
  }

  private static final class ChildProcessPropertiesPropagationSetter
      implements AgentPropagation.Setter<Map<String, String>> {
    static final AgentPropagation.Setter<Map<String, String>> INSTANCE =
        new ChildProcessPropertiesPropagationSetter();

    private ChildProcessPropertiesPropagationSetter() {}

    @Override
    public void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value);
    }
  }

  private Map<String, String> getPropertiesPropagatedToChildProcess(
      String moduleName,
      String startCommand,
      @Nullable Collection<Path> classpath,
      @Nullable JavaAgent jacocoAgent,
      InetSocketAddress signalServerAddress,
      ExecutionSettings executionSettings,
      BuildSessionSettings sessionSettings) {
    Map<String, String> propagatedSystemProperties = new HashMap<>();
    Properties systemProperties = System.getProperties();
    for (Map.Entry<Object, Object> e : systemProperties.entrySet()) {
      String propertyName = (String) e.getKey();
      Object propertyValue = e.getValue();
      if ((propertyName.startsWith(Config.PREFIX)
              || propertyName.startsWith("datadog.slf4j.simpleLogger.defaultLogLevel"))
          && propertyValue != null) {
        propagatedSystemProperties.put(propertyName, propertyValue.toString());
      }
    }

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED),
        Boolean.toString(executionSettings.isItrEnabled()));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED),
        Boolean.toString(executionSettings.isCodeCoverageEnabled()));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_TEST_SKIPPING_ENABLED),
        Boolean.toString(executionSettings.isTestSkippingEnabled()));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ENABLED),
        Boolean.toString(executionSettings.isFlakyTestRetriesEnabled()));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED),
        Boolean.toString(executionSettings.getEarlyFlakeDetectionSettings().isEnabled()));

    // explicitly disable build instrumentation in child processes,
    // because some projects run "embedded" Maven/Gradle builds as part of their integration tests,
    // and we don't want to show those as if they were regular build executions
    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED),
        Boolean.toString(false));

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_INJECTED_TRACER_VERSION),
        TracerVersion.TRACER_VERSION);

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_NAME),
        moduleName);
    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_TEST_COMMAND),
        startCommand);

    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST),
        signalServerAddress != null ? signalServerAddress.getHostName() : null);
    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT),
        String.valueOf(signalServerAddress != null ? signalServerAddress.getPort() : 0));

    List<String> coverageIncludedPackages = sessionSettings.getCoverageIncludedPackages();
    propagatedSystemProperties.put(
        Strings.propertyNameToSystemPropertyName(
            CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_INCLUDES),
        String.join(":", coverageIncludedPackages));

    if (jacocoAgent != null && !config.isCiVisibilityCoverageLinesDisabled()) {
      // If the module is using Jacoco,
      // per-test code coverage needs to have line-level granularity
      // (as opposed to default file-level granularity).
      // Line coverage data is needed to back-fill coverage for tests skipped by ITR
      // in order to calculate accurate coverage percentage.
      // This setting is only relevant if per-test code coverage is enabled,
      // otherwise it has no effect.
      propagatedSystemProperties.put(
          Strings.propertyNameToSystemPropertyName(
              CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED),
          Boolean.toString(true));
    }

    // propagate module span context to child processes
    propagate()
        .inject(span, propagatedSystemProperties, ChildProcessPropertiesPropagationSetter.INSTANCE);

    return propagatedSystemProperties;
  }

  @Override
  public long getId() {
    return span.getSpanId();
  }

  @Override
  public BuildModuleSettings getSettings() {
    return settings;
  }

  /**
   * Handles module execution results received from a forked JVM.
   *
   * <p>Depending on the build configuration it is possible to have multiple forks created for the
   * same module: in Gradle this is achieved with {@code maxParallelForks} or {@code forkEvery}
   * properties of the Test task, in Maven - with {@code forkCount>} property of the Surefire
   * plugin. The forks can execute either concurrently or sequentially.
   *
   * <p>Taking this into account, the method should merge execution results rather than overwrite
   * them.
   *
   * <p>This method is called by the {@link
   * datadog.trace.util.AgentThreadFactory.AgentThread#CI_SIGNAL_SERVER} thread.
   *
   * @param result Module execution results received from a forked JVM.
   */
  private SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    if (result.isCoverageEnabled()) {
      codeCoverageEnabled = true;
    }
    if (result.isTestSkippingEnabled()) {
      testSkippingEnabled = true;
    }
    if (result.isEarlyFlakeDetectionEnabled()) {
      setTag(Tags.TEST_EARLY_FLAKE_ENABLED, true);
      if (result.isEarlyFlakeDetectionFaulty()) {
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, CIConstants.EFD_ABORT_REASON_FAULTY);
      }
    }

    testsSkipped.add(result.getTestsSkippedTotal());

    SpanUtils.mergeTestFrameworks(span, result.getTestFrameworks());

    return AckResponse.INSTANCE;
  }

  @Override
  public void end(@Nullable Long endTime) {
    if (codeCoverageEnabled) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (testSkippingEnabled) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = testsSkipped.sum();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    Long coveragePercentage = coverageCalculator.calculateCoveragePercentage();
    if (coveragePercentage != null) {
      setTag(Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE, coveragePercentage);

      if (testSkippingEnabled) {
        setTag(Tags.TEST_CODE_COVERAGE_BACKFILLED, true);
      }
    }

    moduleSignalRouter.removeModuleHandlers(span.getSpanId());

    super.end(endTime);
  }
}
