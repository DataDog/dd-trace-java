package datadog.trace.civisibility.domain.headless;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.LibraryCapability;
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
import datadog.trace.civisibility.Constants;
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
import java.util.Collection;
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
  private final Collection<LibraryCapability> capabilities;

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
      Collection<LibraryCapability> capabilities,
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
    this.capabilities = capabilities;
  }

  @Override
  public boolean isNew(@Nonnull TestIdentifier test) {
    return executionStrategy.isNew(test);
  }

  @Override
  public boolean isModified(@Nonnull TestSourceData testSourceData) {
    return executionStrategy.isModified(testSourceData);
  }

  @Override
  public boolean isQuarantined(TestIdentifier test) {
    return executionStrategy.isQuarantined(test);
  }

  @Override
  public boolean isDisabled(TestIdentifier test) {
    return executionStrategy.isDisabled(test);
  }

  @Override
  public boolean isAttemptToFix(TestIdentifier test) {
    return executionStrategy.isAttemptToFix(test);
  }

  @Nullable
  @Override
  public SkipReason skipReason(TestIdentifier test) {
    return executionStrategy.skipReason(test);
  }

  @Override
  @Nonnull
  public TestExecutionPolicy executionPolicy(
      TestIdentifier test, TestSourceData testSource, Collection<String> testTags) {
    return executionStrategy.executionPolicy(test, testSource, testTags);
  }

  @Override
  public int executionPriority(@Nullable TestIdentifier test, @Nonnull TestSourceData testSource) {
    return executionStrategy.executionPriority(test, testSource);
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
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, Constants.EFD_ABORT_REASON_FAULTY);
      }
    }

    TestManagementSettings testManagementSettings = executionSettings.getTestManagementSettings();
    if (testManagementSettings.isEnabled()) {
      setTag(Tags.TEST_TEST_MANAGEMENT_ENABLED, true);
    }

    if (executionResults.hasFailedTestReplayTests()) {
      setTag(DDTags.TEST_HAS_FAILED_TEST_REPLAY, true);
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
        capabilities,
        tagsPropagator::propagateCiVisibilityTags);
  }
}
