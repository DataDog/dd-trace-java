package datadog.trace.civisibility.domain.headless;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.retry.NeverRetry;
import datadog.trace.civisibility.retry.RetryIfFailed;
import datadog.trace.civisibility.retry.RetryNTimes;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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

  private final LongAdder testsSkipped = new LongAdder();
  private final String itrCorrelationId;
  private final Collection<TestIdentifier> skippableTests;
  private final Collection<TestIdentifier> flakyTests;
  private final Collection<TestIdentifier> knownTests;
  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private final AtomicInteger earlyFlakeDetectionsUsed = new AtomicInteger(0);
  private final boolean codeCoverageEnabled;
  private final boolean itrEnabled;

  public HeadlessTestModule(
      AgentSpan.Context sessionSpanContext,
      long sessionId,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      ModuleExecutionSettings moduleExecutionSettings,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        sessionId,
        moduleName,
        startTime,
        InstrumentationType.HEADLESS,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        onSpanFinish);

    codeCoverageEnabled = moduleExecutionSettings.isCodeCoverageEnabled();
    itrEnabled = moduleExecutionSettings.isItrEnabled();
    itrCorrelationId = moduleExecutionSettings.getItrCorrelationId();
    skippableTests = new HashSet<>(moduleExecutionSettings.getSkippableTests(moduleName));
    flakyTests = new HashSet<>(moduleExecutionSettings.getFlakyTests(moduleName));

    Collection<TestIdentifier> moduleKnownTests = moduleExecutionSettings.getKnownTests(moduleName);
    knownTests = moduleKnownTests != null ? new HashSet<>(moduleKnownTests) : null;

    earlyFlakeDetectionSettings = moduleExecutionSettings.getEarlyFlakeDetectionSettings();
  }

  @Override
  public boolean isSkippable(TestIdentifier test) {
    return test != null && skippableTests.contains(test);
  }

  @Override
  public boolean isNew(TestIdentifier test) {
    return knownTests != null && !knownTests.contains(test.withoutParameters());
  }

  @Override
  public boolean skip(TestIdentifier test) {
    if (isSkippable(test)) {
      testsSkipped.increment();
      return true;
    } else {
      return false;
    }
  }

  @Override
  @Nonnull
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    if (test != null) {
      if (earlyFlakeDetectionSettings.isEnabled()
          && !knownTests.contains(test.withoutParameters())
          && !earlyFlakeDetectionLimitReached(earlyFlakeDetectionsUsed.incrementAndGet())) {
        return new RetryNTimes(earlyFlakeDetectionSettings);
      }
      if (flakyTests.contains(test.withoutParameters())) {
        return new RetryIfFailed(config.getCiVisibilityFlakyRetryCount());
      }
    }
    return NeverRetry.INSTANCE;
  }

  private boolean earlyFlakeDetectionLimitReached(int earlyFlakeDetectionsUsed) {
    int totalTests = knownTests.size() + earlyFlakeDetectionsUsed;
    int threshold =
        Math.max(
            config.getCiVisibilityEarlyFlakeDetectionLowerLimit(),
            totalTests * earlyFlakeDetectionSettings.getFaultySessionThreshold() / 100);
    return earlyFlakeDetectionsUsed > threshold;
  }

  @Override
  public void end(@Nullable Long endTime) {
    if (codeCoverageEnabled) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (itrEnabled) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = testsSkipped.sum();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    if (earlyFlakeDetectionSettings.isEnabled()) {
      setTag(Tags.TEST_EARLY_FLAKE_ENABLED, true);
      if (earlyFlakeDetectionLimitReached(earlyFlakeDetectionsUsed.get())) {
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, "faulty");
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
        sessionId,
        span.getSpanId(),
        moduleName,
        testSuiteName,
        itrCorrelationId,
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
        methodLinesResolver,
        coverageProbeStoreFactory,
        SpanUtils.propagateCiVisibilityTagsTo(span));
  }
}
