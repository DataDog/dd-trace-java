package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.coverage.percentage.child.ChildProcessCoverageReporter;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.ModuleSignal;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.ipc.TestFramework;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionStrategy;
import java.util.Collection;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test module implementation that is used by test framework instrumentations in those cases when
 * the build system IS instrumented: since build system instrumentation manages module spans, this
 * class does not do it. Instead, it accumulates module execution data and forwards it to the parent
 * process (build system) using the signal server
 */
public class ProxyTestModule implements TestFrameworkModule {
  private static final Logger log = LoggerFactory.getLogger(ProxyTestModule.class);

  private final AgentSpan.Context parentProcessModuleContext;
  private final String moduleName;
  private final ExecutionStrategy executionStrategy;
  private final SignalClient.Factory signalClientFactory;
  private final ChildProcessCoverageReporter childProcessCoverageReporter;
  private final Config config;
  private final CiVisibilityMetricCollector metricCollector;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final LinesResolver linesResolver;
  private final CoverageStore.Factory coverageStoreFactory;
  private final Collection<TestFramework> testFrameworks = ConcurrentHashMap.newKeySet();

  public ProxyTestModule(
      AgentSpan.Context parentProcessModuleContext,
      String moduleName,
      ExecutionStrategy executionStrategy,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      CoverageStore.Factory coverageStoreFactory,
      ChildProcessCoverageReporter childProcessCoverageReporter,
      SignalClient.Factory signalClientFactory) {
    this.parentProcessModuleContext = parentProcessModuleContext;
    this.moduleName = moduleName;
    this.executionStrategy = executionStrategy;
    this.signalClientFactory = signalClientFactory;
    this.childProcessCoverageReporter = childProcessCoverageReporter;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.linesResolver = linesResolver;
    this.coverageStoreFactory = coverageStoreFactory;
  }

  @Override
  public boolean isNew(TestIdentifier test) {
    return executionStrategy.isNew(test);
  }

  @Override
  public boolean isFlaky(TestIdentifier test) {
    return executionStrategy.isFlaky(test);
  }

  @Override
  public boolean shouldBeSkipped(TestIdentifier test) {
    return executionStrategy.shouldBeSkipped(test);
  }

  @Override
  public boolean skip(TestIdentifier test) {
    return executionStrategy.skip(test);
  }

  @Override
  @Nonnull
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    return executionStrategy.retryPolicy(test);
  }

  @Override
  public void end(@Nullable Long endTime) {
    // we have no span locally,
    // send execution result to parent process that manages the span
    sendModuleExecutionResult();
  }

  private void sendModuleExecutionResult() {
    DDTraceId parentProcessSessionId = parentProcessModuleContext.getTraceId();
    long parentProcessModuleId = parentProcessModuleContext.getSpanId();

    try (SignalClient signalClient = signalClientFactory.create()) {
      ModuleSignal coverageSignal =
          childProcessCoverageReporter.createCoverageSignal(
              parentProcessSessionId, parentProcessModuleId);
      if (coverageSignal != null) {
        signalClient.send(coverageSignal);
      }

      boolean coverageEnabled = config.isCiVisibilityCodeCoverageEnabled();
      boolean testSkippingEnabled = config.isCiVisibilityTestSkippingEnabled();

      ExecutionSettings executionSettings = executionStrategy.getExecutionSettings();
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
          executionSettings.getEarlyFlakeDetectionSettings();
      boolean earlyFlakeDetectionEnabled = earlyFlakeDetectionSettings.isEnabled();
      boolean earlyFlakeDetectionFaulty =
          earlyFlakeDetectionEnabled && executionStrategy.isEarlyFlakeDetectionLimitReached();
      long testsSkippedTotal = executionStrategy.getTestsSkipped();

      signalClient.send(
          new ModuleExecutionResult(
              parentProcessSessionId,
              parentProcessModuleId,
              coverageEnabled,
              testSkippingEnabled,
              earlyFlakeDetectionEnabled,
              earlyFlakeDetectionFaulty,
              testsSkippedTotal,
              new TreeSet<>(testFrameworks)));

    } catch (Exception e) {
      log.error("Error while reporting module execution result", e);
    }
  }

  @Override
  public TestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation) {
    return new TestSuiteImpl(
        parentProcessModuleContext,
        moduleName,
        testSuiteName,
        executionStrategy.getExecutionSettings().getItrCorrelationId(),
        testClass,
        startTime,
        parallelized,
        InstrumentationType.BUILD,
        instrumentation,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        coverageStoreFactory,
        this::propagateTestFrameworkData);
  }

  private void propagateTestFrameworkData(AgentSpan childSpan) {
    testFrameworks.add(
        new TestFramework(
            (String) childSpan.getTag(Tags.TEST_FRAMEWORK),
            (String) childSpan.getTag(Tags.TEST_FRAMEWORK_VERSION)));
  }
}
