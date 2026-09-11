package datadog.trace.civisibility.test;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.tag.HasCustomBuckets;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.TestManagementSettings;
import datadog.trace.civisibility.config.TestSetting;
import datadog.trace.civisibility.execution.AttemptToFix;
import datadog.trace.civisibility.execution.AutoTestRetry;
import datadog.trace.civisibility.execution.DynamicAutoTestRetry;
import datadog.trace.civisibility.execution.EarlyFlakeDetection;
import datadog.trace.civisibility.execution.Quarantine;
import datadog.trace.civisibility.execution.Regular;
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
  @Nonnull private final CiVisibilityMetricCollector metricCollector;
  private final int[] dynamicAtrBuckets;

  public ExecutionStrategy(
      @Nonnull Config config,
      @Nonnull ExecutionSettings executionSettings,
      @Nonnull SourcePathResolver sourcePathResolver,
      @Nonnull LinesResolver linesResolver,
      @Nonnull CiVisibilityMetricCollector metricCollector) {
    this.config = config;
    this.executionSettings = executionSettings;
    this.sourcePathResolver = sourcePathResolver;
    this.linesResolver = linesResolver;
    this.metricCollector = metricCollector;
    this.dynamicAtrBuckets = parseDynamicAtrBuckets(config.getCiVisibilityDynamicAtrBuckets());

    if (config.isCiVisibilityDynamicAtrEnabled()
        && executionSettings.isFlakyTestRetriesEnabled()) {
      metricCollector.add(
          CiVisibilityCountMetric.DYNAMIC_ATR_RETRIES_ENABLED,
          1,
          dynamicAtrBuckets != null ? HasCustomBuckets.TRUE : null);
    }
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
      return new AttemptToFix(
          executionSettings.getTestManagementSettings().getAttemptToFixRetries());
    }

    if (isEFDApplicable(test, testSource, testTags)) {
      // check-then-act with "earlyFlakeDetectionsUsed" is not atomic here,
      // but we don't care if we go "a bit" over the limit, it does not have to be precise
      earlyFlakeDetectionsUsed.incrementAndGet();
      return new EarlyFlakeDetection(
          executionSettings.getEarlyFlakeDetectionSettings(),
          isQuarantined(test));
    }

    if (isAutoRetryApplicable(test)) {
      // check-then-act with "autoRetriesUsed" is not atomic here,
      // but we don't care if we go "a bit" over the limit, it does not have to be precise
      if (config.isCiVisibilityDynamicAtrEnabled()) {
        return new DynamicAutoTestRetry(
            executionSettings.getEarlyFlakeDetectionSettings(),
            dynamicAtrBuckets,
            isQuarantined(test),
            autoRetriesUsed);
      }
      return new AutoTestRetry(
          config.getCiVisibilityFlakyRetryCount(), isQuarantined(test), autoRetriesUsed);
    }

    if (isQuarantined(test)) {
      return new Quarantine();
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
      Collection<String> sourcePaths = sourcePathResolver.getSourcePaths(testClass);
      if (sourcePaths.size() != 1) {
        return false;
      }
      String sourcePath = sourcePaths.iterator().next();

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

  private static final int RETRY_BUCKET_COUNT = 5;
  private static final int MAX_RETRIES_PER_BUCKET = 20;

  /**
   * Parses the {@code DD_CIVISIBILITY_DYNAMIC_ATR_BUCKETS} env var into five positive integers in
   * [1, 20]. Returns {@code null} if the value is unset/empty or invalid (wrong count,
   * non-integer, out of range) — in which case the EFD retry settings are used as fallback.
   */
  private static int[] parseDynamicAtrBuckets(String rawBuckets) {
    if (rawBuckets == null || rawBuckets.isEmpty()) {
      return null;
    }
    String[] parts = rawBuckets.split(",", -1);
    if (parts.length != RETRY_BUCKET_COUNT) {
      LOGGER.warn(
          "Invalid {} value '{}'; expected five comma-separated integers in [1, {}]",
          "DD_CIVISIBILITY_DYNAMIC_ATR_BUCKETS",
          rawBuckets,
          MAX_RETRIES_PER_BUCKET);
      return null;
    }
    int[] buckets = new int[RETRY_BUCKET_COUNT];
    try {
      for (int i = 0; i < RETRY_BUCKET_COUNT; i++) {
        int value = Integer.parseInt(parts[i].trim());
        if (value < 1 || value > MAX_RETRIES_PER_BUCKET) {
          LOGGER.warn(
              "Invalid {} value '{}'; expected five comma-separated integers in [1, {}]",
              "DD_CIVISIBILITY_DYNAMIC_ATR_BUCKETS",
              rawBuckets,
              MAX_RETRIES_PER_BUCKET);
          return null;
        }
        buckets[i] = value;
      }
    } catch (NumberFormatException e) {
      LOGGER.warn(
          "Invalid {} value '{}'; expected five comma-separated integers in [1, {}]",
          "DD_CIVISIBILITY_DYNAMIC_ATR_BUCKETS",
          rawBuckets,
          MAX_RETRIES_PER_BUCKET);
      return null;
    }
    return buckets;
  }
}
