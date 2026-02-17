package datadog.trace.civisibility.test;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.TestManagementSettings;
import datadog.trace.civisibility.config.TestSetting;
import datadog.trace.civisibility.execution.Regular;
import datadog.trace.civisibility.execution.RetryUntilSuccessful;
import datadog.trace.civisibility.execution.RunNTimes;
import datadog.trace.civisibility.execution.RunOnceIgnoreOutcome;
import datadog.trace.civisibility.execution.exit.ExitOnFailure;
import datadog.trace.civisibility.execution.exit.ExitOnFlake;
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

  public boolean isNew(@Nonnull TestIdentifier test) {
    return executionSettings.isKnownTestsDataAvailable()
        && !executionSettings.isKnown(test.toFQN());
  }

  private boolean isFlaky(@Nonnull TestIdentifier test) {
    return executionSettings.isFlaky(test.toFQN());
  }

  public boolean isQuarantined(TestIdentifier test) {
    TestManagementSettings testManagementSettings = executionSettings.getTestManagementSettings();
    if (!testManagementSettings.isEnabled()) {
      return false;
    }
    return executionSettings.isQuarantined(test.toFQN());
  }

  public boolean isDisabled(TestIdentifier test) {
    TestManagementSettings testManagementSettings = executionSettings.getTestManagementSettings();
    if (!testManagementSettings.isEnabled()) {
      return false;
    }
    return executionSettings.isDisabled(test.toFQN());
  }

  public boolean isAttemptToFix(TestIdentifier test) {
    TestManagementSettings testManagementSettings = executionSettings.getTestManagementSettings();
    if (!testManagementSettings.isEnabled()) {
      return false;
    }
    return executionSettings.isAttemptToFix(test.toFQN());
  }

  @Nullable
  public SkipReason skipReason(TestIdentifier test) {
    if (test == null) {
      return null;
    }

    // test should not be skipped if it is an attempt to fix, independent of TIA or Disabled
    if (isAttemptToFix(test)) {
      return null;
    }

    if (isDisabled(test)) {
      return SkipReason.DISABLED;
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
  public TestExecutionPolicy executionPolicy(
      TestIdentifier test, TestSourceData testSource, Collection<String> testTags) {
    if (test == null) {
      return Regular.INSTANCE;
    }

    if (isAttemptToFix(test)) {
      return new RunNTimes(
          executionSettings.getTestManagementSettings().getAttemptToFixExecutions(),
          isQuarantined(test) || isDisabled(test),
          RetryReason.attemptToFix,
          ExitOnFailure.INSTANCE);
    }

    if (isEFDApplicable(test, testSource, testTags)) {
      // check-then-act with "earlyFlakeDetectionsUsed" is not atomic here,
      // but we don't care if we go "a bit" over the limit, it does not have to be precise
      earlyFlakeDetectionsUsed.incrementAndGet();
      return new RunNTimes(
          executionSettings.getEarlyFlakeDetectionSettings().getExecutionsByDuration(),
          isQuarantined(test),
          RetryReason.efd,
          ExitOnFlake.INSTANCE);
    }

    if (isAutoRetryApplicable(test)) {
      // check-then-act with "autoRetriesUsed" is not atomic here,
      // but we don't care if we go "a bit" over the limit, it does not have to be precise
      return new RetryUntilSuccessful(
          config.getCiVisibilityFlakyRetryCount(), isQuarantined(test), autoRetriesUsed);
    }

    if (isQuarantined(test)) {
      return new RunOnceIgnoreOutcome();
    }

    return Regular.INSTANCE;
  }

  private boolean isAutoRetryApplicable(TestIdentifier test) {
    if (!executionSettings.isFlakyTestRetriesEnabled()) {
      return false;
    }

    return (!executionSettings.isFlakyTestsDataAvailable()
            || executionSettings.isFlaky(test.toFQN()))
        && autoRetriesUsed.get() < config.getCiVisibilityTotalFlakyRetryCount();
  }

  private boolean isEFDApplicable(
      @Nonnull TestIdentifier test, TestSourceData testSource, Collection<String> testTags) {
    EarlyFlakeDetectionSettings efdSettings = executionSettings.getEarlyFlakeDetectionSettings();
    return efdSettings.isEnabled()
        && !isEFDLimitReached()
        && (isNew(test) || isModified(testSource))
        // endsWith matching is needed for JUnit4-based frameworks, where tags are classes
        && testTags.stream().noneMatch(t -> t.endsWith(CIConstants.Tags.EFD_DISABLE_TAG));
  }

  public boolean isEFDLimitReached() {
    if (!executionSettings.isKnownTestsDataAvailable()) {
      return false;
    }

    int detectionsUsed = earlyFlakeDetectionsUsed.get();
    int totalTests = executionSettings.getSettingCount(TestSetting.KNOWN) + detectionsUsed;
    EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
        executionSettings.getEarlyFlakeDetectionSettings();
    int threshold =
        Math.max(
            config.getCiVisibilityEarlyFlakeDetectionLowerLimit(),
            totalTests * earlyFlakeDetectionSettings.getFaultySessionThreshold() / 100);

    return detectionsUsed > threshold;
  }

  public boolean isModified(@Nonnull TestSourceData testSourceData) {
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
      LOGGER.debug("Could not determine if {} was modified, assuming false", testSourceData, e);
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

  /**
   * Returns the priority of the test execution that can be used for ordering tests. The higher the
   * value, the higher the priority, meaning that the test should be executed earlier.
   */
  public int executionPriority(@Nullable TestIdentifier test, @Nonnull TestSourceData testSource) {
    if (test == null) {
      return 0;
    }
    if (isNew(test)) {
      // execute new tests first
      return 300;
    }
    if (isModified(testSource)) {
      // then modified tests
      return 200;
    }
    if (isFlaky(test)) {
      // then tests known to be flaky
      return 100;
    }
    // then the rest
    return 0;
  }
}
