package datadog.trace.civisibility.test;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.retry.NeverRetry;
import datadog.trace.civisibility.retry.RetryIfFailed;
import datadog.trace.civisibility.retry.RetryNTimes;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;

public class ExecutionStrategy {

  private final LongAdder testsSkipped = new LongAdder();
  private final AtomicInteger earlyFlakeDetectionsUsed = new AtomicInteger(0);
  private final AtomicInteger autoRetriesUsed = new AtomicInteger(0);

  @Nonnull private final Config config;
  @Nonnull private final ExecutionSettings executionSettings;

  public ExecutionStrategy(@Nonnull Config config, @Nonnull ExecutionSettings executionSettings) {
    this.config = config;
    this.executionSettings = executionSettings;
  }

  @Nonnull
  public ExecutionSettings getExecutionSettings() {
    return executionSettings;
  }

  public long getTestsSkipped() {
    return testsSkipped.sum();
  }

  public boolean isNew(TestIdentifier test) {
    Collection<TestIdentifier> knownTests = executionSettings.getKnownTests();
    return knownTests != null && !knownTests.contains(test.withoutParameters());
  }

  public boolean isFlaky(TestIdentifier test) {
    Collection<TestIdentifier> flakyTests = executionSettings.getFlakyTests();
    return flakyTests != null && flakyTests.contains(test.withoutParameters());
  }

  public boolean shouldBeSkipped(TestIdentifier test) {
    if (test == null) {
      return false;
    }
    if (!executionSettings.isTestSkippingEnabled()) {
      return false;
    }
    Map<TestIdentifier, TestMetadata> skippableTests = executionSettings.getSkippableTests();
    TestMetadata testMetadata = skippableTests.get(test);
    return testMetadata != null
        && !(config.isCiVisibilityCoverageLinesEnabled()
            && testMetadata.isMissingLineCodeCoverage());
  }

  public boolean skip(TestIdentifier test) {
    if (shouldBeSkipped(test)) {
      testsSkipped.increment();
      return true;
    } else {
      return false;
    }
  }

  @Nonnull
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    if (test != null) {
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
          executionSettings.getEarlyFlakeDetectionSettings();
      if (earlyFlakeDetectionSettings.isEnabled()) {
        Collection<TestIdentifier> knownTests = executionSettings.getKnownTests();
        if (knownTests != null
            && !knownTests.contains(test.withoutParameters())
            && !isEarlyFlakeDetectionLimitReached()) {
          // check-then-act with "earlyFlakeDetectionsUsed" is not atomic here,
          // but we don't care if we go "a bit" over the limit, it does not have to be precise
          earlyFlakeDetectionsUsed.incrementAndGet();
          return new RetryNTimes(earlyFlakeDetectionSettings);
        }
      }

      if (executionSettings.isFlakyTestRetriesEnabled()) {
        Collection<TestIdentifier> flakyTests = executionSettings.getFlakyTests();
        if ((flakyTests == null || flakyTests.contains(test.withoutParameters()))
            && autoRetriesUsed.get() < config.getCiVisibilityTotalFlakyRetryCount()) {
          // check-then-act with "autoRetriesUsed" is not atomic here,
          // but we don't care if we go "a bit" over the limit, it does not have to be precise
          return new RetryIfFailed(config.getCiVisibilityFlakyRetryCount(), autoRetriesUsed);
        }
      }
    }
    return NeverRetry.INSTANCE;
  }

  public boolean isEarlyFlakeDetectionLimitReached() {
    int detectionsUsed = earlyFlakeDetectionsUsed.get();
    Collection<TestIdentifier> knownTests = executionSettings.getKnownTests();
    if (knownTests == null) {
      return false;
    }

    int totalTests = knownTests.size() + detectionsUsed;
    EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
        executionSettings.getEarlyFlakeDetectionSettings();
    int threshold =
        Math.max(
            config.getCiVisibilityEarlyFlakeDetectionLowerLimit(),
            totalTests * earlyFlakeDetectionSettings.getFaultySessionThreshold() / 100);

    return detectionsUsed > threshold;
  }
}
