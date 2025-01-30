package datadog.trace.civisibility.test;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.execution.Regular;
import datadog.trace.civisibility.execution.RetryUntilSuccessful;
import datadog.trace.civisibility.execution.RunNTimes;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionStrategy.class);

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

  public boolean isNew(TestIdentifier test) {
    Collection<TestIdentifier> knownTests = executionSettings.getKnownTests();
    return knownTests != null && !knownTests.contains(test.withoutParameters());
  }

  public boolean isFlaky(TestIdentifier test) {
    Collection<TestIdentifier> flakyTests = executionSettings.getFlakyTests();
    return flakyTests != null && flakyTests.contains(test.withoutParameters());
  }

  @Nullable
  public SkipReason skipReason(TestIdentifier test) {
    if (test == null) {
      return null;
    }
    if (!executionSettings.isTestSkippingEnabled()) {
      return null;
    }
    Map<TestIdentifier, TestMetadata> skippableTests = executionSettings.getSkippableTests();
    TestMetadata testMetadata = skippableTests.get(test);
    if (testMetadata == null) {
      return null;
    }
    if (config.isCiVisibilityCoverageLinesEnabled() && testMetadata.isMissingLineCodeCoverage()) {
      return null;
    }
    return SkipReason.ITR;
  }

  @Nonnull
  public TestExecutionPolicy executionPolicy(TestIdentifier test, TestSourceData testSource) {
    if (test == null) {
      return Regular.INSTANCE;
    }

    EarlyFlakeDetectionSettings efdSettings = executionSettings.getEarlyFlakeDetectionSettings();
    if (efdSettings.isEnabled() && !isEFDLimitReached()) {
      if (isNew(test) || isModified(testSource)) {
        // check-then-act with "earlyFlakeDetectionsUsed" is not atomic here,
        // but we don't care if we go "a bit" over the limit, it does not have to be precise
        earlyFlakeDetectionsUsed.incrementAndGet();
        return new RunNTimes(efdSettings);
      }
    }

    if (executionSettings.isFlakyTestRetriesEnabled()) {
      Collection<TestIdentifier> flakyTests = executionSettings.getFlakyTests();
      if ((flakyTests == null || flakyTests.contains(test.withoutParameters()))
          && autoRetriesUsed.get() < config.getCiVisibilityTotalFlakyRetryCount()) {
        // check-then-act with "autoRetriesUsed" is not atomic here,
        // but we don't care if we go "a bit" over the limit, it does not have to be precise
        return new RetryUntilSuccessful(config.getCiVisibilityFlakyRetryCount(), autoRetriesUsed);
      }
    }

    return Regular.INSTANCE;
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
