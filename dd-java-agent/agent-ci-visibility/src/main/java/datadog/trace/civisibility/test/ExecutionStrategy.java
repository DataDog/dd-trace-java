package datadog.trace.civisibility.test;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.retry.NeverRetry;
import datadog.trace.civisibility.retry.RetryIfFailed;
import datadog.trace.civisibility.retry.RetryNTimes;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionStrategy.class);

  private final LongAdder testsSkipped = new LongAdder();
  private final AtomicInteger earlyFlakeDetectionsUsed = new AtomicInteger(0);
  private final AtomicInteger autoRetriesUsed = new AtomicInteger(0);

  @Nonnull private final Config config;
  @Nonnull private final ExecutionSettings executionSettings;
  @Nonnull private final SourcePathResolver sourcePathResolver;
  @Nonnull private final LinesResolver linesResolver;

  public ExecutionStrategy(
      @Nonnull Config config,
      @Nonnull ExecutionSettings executionSettings,
      @Nonnull SourcePathResolver sourcePathResolver,
      @Nonnull LinesResolver linesResolver) {
    this.config = config;
    this.executionSettings = executionSettings;
    this.sourcePathResolver = sourcePathResolver;
    this.linesResolver = linesResolver;
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
  public TestRetryPolicy retryPolicy(TestIdentifier test, TestSourceData testSource) {
    if (test == null) {
      return NeverRetry.INSTANCE;
    }

    EarlyFlakeDetectionSettings efdSettings = executionSettings.getEarlyFlakeDetectionSettings();
    if (efdSettings.isEnabled() && !isEFDLimitReached()) {
      if (isNew(test) || isModified(testSource)) {
        // check-then-act with "earlyFlakeDetectionsUsed" is not atomic here,
        // but we don't care if we go "a bit" over the limit, it does not have to be precise
        earlyFlakeDetectionsUsed.incrementAndGet();
        return new RetryNTimes(efdSettings);
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
    return NeverRetry.INSTANCE;
  }

  public boolean isEFDLimitReached() {
    Collection<TestIdentifier> knownTests = executionSettings.getKnownTests();
    if (knownTests == null) {
      return false;
    }

    int detectionsUsed = earlyFlakeDetectionsUsed.get();
    int totalTests = knownTests.size() + detectionsUsed;
    EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
        executionSettings.getEarlyFlakeDetectionSettings();
    int threshold =
        Math.max(
            config.getCiVisibilityEarlyFlakeDetectionLowerLimit(),
            totalTests * earlyFlakeDetectionSettings.getFaultySessionThreshold() / 100);

    return detectionsUsed > threshold;
  }

  public boolean isModified(TestSourceData testSourceData) {
    Class<?> testClass = testSourceData.getTestClass();
    if (testClass == null) {
      return false;
    }
    try {
      String sourcePath = sourcePathResolver.getSourcePath(testClass);
      if (sourcePath == null) {
        return false;
      }

      LinesResolver.Lines lines = getLines(testSourceData.getTestMethod());
      return executionSettings
          .getPullRequestDiff()
          .contains(sourcePath, lines.getStartLineNumber(), lines.getEndLineNumber());

    } catch (Exception e) {
      LOGGER.error("Could not determine if {} was modified, assuming false", testSourceData, e);
      return false;
    }
  }

  private LinesResolver.Lines getLines(Method testMethod) {
    if (testMethod == null) {
      // method for this test case could not be determined,
      // so we fall back to lower granularity
      // and assume that the test was modified if there were any changes in the file
      return new LinesResolver.Lines(0, Integer.MAX_VALUE);
    } else {
      return linesResolver.getMethodLines(testMethod);
    }
  }
}
