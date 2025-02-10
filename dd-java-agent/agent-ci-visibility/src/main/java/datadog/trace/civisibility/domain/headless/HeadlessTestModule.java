package datadog.trace.civisibility.domain.headless;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.TestManagementSettings;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionResults;
import datadog.trace.civisibility.test.ExecutionStrategy;
import datadog.trace.civisibility.utils.SpanUtils;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Test module implementation that is used when the tracer is running in "headless" mode, meaning
 * that only child processes that are forked to execute tests are instrumented, and the parent build
 * system process is NOT instrumented.
 *
 * <p>This class manages the module span since there is no build system instrumentation to do it.
 */
public class HeadlessTestModule extends AbstractTestModule implements TestFrameworkModule {

  private final CoverageStore.Factory coverageStoreFactory;
  private final ExecutionStrategy executionStrategy;
  private final ExecutionResults executionResults;

  public HeadlessTestModule(
      AgentSpanContext sessionSpanContext,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      CoverageStore.Factory coverageStoreFactory,
      ExecutionStrategy executionStrategy,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        moduleName,
        startTime,
        InstrumentationType.HEADLESS,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        onSpanFinish);
    this.coverageStoreFactory = coverageStoreFactory;
    this.executionStrategy = executionStrategy;
    this.executionResults = new ExecutionResults();
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
  public boolean isModified(TestSourceData testSourceData) {
    return executionStrategy.isModified(testSourceData);
  }

  @Override
  public boolean isQuarantined(TestIdentifier test) {
    return executionStrategy.isQuarantined(test);
  }

  @Nullable
  @Override
  public SkipReason skipReason(TestIdentifier test) {
    return executionStrategy.skipReason(test);
  }

  @Override
  @Nonnull
  public TestExecutionPolicy executionPolicy(TestIdentifier test, TestSourceData testSource) {
    return executionStrategy.executionPolicy(test, testSource);
  }

  @Override
  public void end(@Nullable Long endTime) {
    ExecutionSettings executionSettings = executionStrategy.getExecutionSettings();
    if (executionSettings.isCodeCoverageEnabled()) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (executionSettings.isTestSkippingEnabled()) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = executionResults.getTestsSkippedByItr();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
        executionSettings.getEarlyFlakeDetectionSettings();
    if (earlyFlakeDetectionSettings.isEnabled()) {
      setTag(Tags.TEST_EARLY_FLAKE_ENABLED, true);
      if (executionStrategy.isEFDLimitReached()) {
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, CIConstants.EFD_ABORT_REASON_FAULTY);
      }
    }

    TestManagementSettings testManagementSettings = executionSettings.getTestManagementSettings();
    if (testManagementSettings.isEnabled()) {
      setTag(Tags.TEST_TEST_MANAGEMENT_ENABLED, true);
    }

    super.end(endTime);
  }

  @Override
  public TestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation) {
    return new TestSuiteImpl(
        span.context(),
        moduleName,
        testSuiteName,
        executionStrategy.getExecutionSettings().getItrCorrelationId(),
        testClass,
        startTime,
        parallelized,
        InstrumentationType.HEADLESS,
        instrumentation,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        coverageStoreFactory,
        executionResults,
        SpanUtils.propagateCiVisibilityTagsTo(span));
  }
}
