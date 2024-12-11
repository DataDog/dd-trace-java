package datadog.trace.civisibility.domain.headless;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
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
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
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

  public HeadlessTestModule(
      AgentSpan.Context sessionSpanContext,
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
    ExecutionSettings executionSettings = executionStrategy.getExecutionSettings();
    if (executionSettings.isCodeCoverageEnabled()) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (executionSettings.isTestSkippingEnabled()) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = executionStrategy.getTestsSkipped();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
        executionSettings.getEarlyFlakeDetectionSettings();
    if (earlyFlakeDetectionSettings.isEnabled()) {
      setTag(Tags.TEST_EARLY_FLAKE_ENABLED, true);
      if (executionStrategy.isEarlyFlakeDetectionLimitReached()) {
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, CIConstants.EFD_ABORT_REASON_FAULTY);
      }
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
        SpanUtils.propagateCiVisibilityTagsTo(span));
  }
}
