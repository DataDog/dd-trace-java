package datadog.trace.civisibility.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricData;
import datadog.trace.api.civisibility.telemetry.TagValue;
import datadog.trace.api.civisibility.telemetry.tag.HasCustomBuckets;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.ExecutionsByDuration;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionStrategy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DynamicAutoTestRetryTest {

  private static final int BUCKET_5S_MILLIS = 5_000;
  private static final int BUCKET_10S_MILLIS = 10_000;
  private static final int BUCKET_30S_MILLIS = 30_000;
  private static final int BUCKET_5M_MILLIS = 300_000;

  private static EarlyFlakeDetectionSettings efdSettings(
      int retries5s, int retries10s, int retries30s, int retries5m) {
    List<ExecutionsByDuration> durations =
        Arrays.asList(
            new ExecutionsByDuration(BUCKET_5S_MILLIS, retries5s),
            new ExecutionsByDuration(BUCKET_10S_MILLIS, retries10s),
            new ExecutionsByDuration(BUCKET_30S_MILLIS, retries30s),
            new ExecutionsByDuration(BUCKET_5M_MILLIS, retries5m));
    return new EarlyFlakeDetectionSettings(false, durations, -1);
  }

  private static ExecutionSettings executionSettings(
      boolean autoRetryEnabled, EarlyFlakeDetectionSettings efdSettings) {
    return new ExecutionSettings(
        false,
        false,
        false,
        autoRetryEnabled,
        false,
        false,
        false,
        efdSettings,
        datadog.trace.civisibility.config.TestManagementSettings.DEFAULT,
        null,
        Collections.emptyMap(),
        Collections.emptyMap(),
        null,
        null,
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        datadog.trace.civisibility.diff.LineDiff.EMPTY,
        datadog.trace.civisibility.config.ConfigurationErrors.NONE);
  }

  // ---- DynamicAutoTestRetry unit tests ----

  @ParameterizedTest(name = "duration={0}ms -> retries={1}")
  @MethodSource("efdBucketsProvider")
  void testDynamicAtrUsesEfdRetryBudgets(long durationMillis, int expectedRetries) {
    EarlyFlakeDetectionSettings settings = efdSettings(10, 2, 3, 4);
    AtomicInteger totalRetries = new AtomicInteger(0);
    DynamicAutoTestRetry policy = new DynamicAutoTestRetry(settings, null, false, totalRetries);

    int retries = 0;
    while (true) {
      policy.registerExecution(TestStatus.fail, durationMillis);
      if (!policy.applicable()) {
        break;
      }
      retries++;
    }
    assertEquals(expectedRetries, retries);
  }

  static Stream<Arguments> efdBucketsProvider() {
    return Stream.of(
        arguments(1_000L, 10), // 5s bucket -> 10 retries
        arguments(6_000L, 2), // 10s bucket -> 2 retries
        arguments(31_000L, 4), // 5m bucket (31s > 30s) -> 4 retries
        arguments(301_000L, 1) // >5m bucket -> 0 retries, but max(1,0)=1
        );
  }

  @ParameterizedTest(name = "duration={0}ms -> retries={1}")
  @MethodSource("customBucketsProvider")
  void testDynamicAtrUsesCustomRetryBudgets(long durationMillis, int expectedRetries) {
    EarlyFlakeDetectionSettings settings = efdSettings(10, 2, 3, 4);
    AtomicInteger totalRetries = new AtomicInteger(0);
    int[] customBuckets = {4, 1, 1, 1, 1};
    DynamicAutoTestRetry policy =
        new DynamicAutoTestRetry(settings, customBuckets, false, totalRetries);

    int retries = 0;
    while (true) {
      policy.registerExecution(TestStatus.fail, durationMillis);
      if (!policy.applicable()) {
        break;
      }
      retries++;
    }
    assertEquals(expectedRetries, retries);
  }

  static Stream<Arguments> customBucketsProvider() {
    return Stream.of(
        arguments(1_000L, 4), // 5s bucket -> 4 retries (custom)
        arguments(6_000L, 1), // 10s bucket -> 1 retry (custom)
        arguments(31_000L, 1), // 30s bucket -> 1 retry (custom)
        arguments(301_000L, 1) // >5m bucket -> 1 retry (custom)
        );
  }

  @Test
  void testDynamicAtrStopsAfterFirstPass() {
    EarlyFlakeDetectionSettings settings = efdSettings(5, 1, 1, 1);
    AtomicInteger totalRetries = new AtomicInteger(0);
    int[] customBuckets = {5, 1, 1, 1, 1};
    DynamicAutoTestRetry policy =
        new DynamicAutoTestRetry(settings, customBuckets, false, totalRetries);

    // first attempt fails
    policy.registerExecution(TestStatus.fail, 1_000);
    assertTrue(policy.applicable());

    // second attempt passes -> should stop
    policy.registerExecution(TestStatus.pass, 1_000);
    assertFalse(policy.applicable());
    assertEquals(1, totalRetries.get());
  }

  @Test
  void testDynamicAtrClassificationIsCached() {
    EarlyFlakeDetectionSettings settings = efdSettings(10, 2, 3, 4);
    AtomicInteger totalRetries = new AtomicInteger(0);
    DynamicAutoTestRetry policy =
        new DynamicAutoTestRetry(settings, null, false, totalRetries);

    // first attempt: 1s duration -> 5s bucket -> 10 retries
    policy.registerExecution(TestStatus.fail, 1_000);
    assertTrue(policy.applicable());

    // second attempt: 600s duration -> should still use 10 retries (initial duration cached)
    policy.registerExecution(TestStatus.fail, 600_000);
    assertTrue(policy.applicable());
  }

  @Test
  void testDynamicAtrMinOneRetry() {
    // EFD bucket with 0 retries for >5m -> max(1, 0) = 1 retry
    EarlyFlakeDetectionSettings settings = efdSettings(10, 2, 3, 4);
    AtomicInteger totalRetries = new AtomicInteger(0);
    DynamicAutoTestRetry policy =
        new DynamicAutoTestRetry(settings, null, false, totalRetries);

    policy.registerExecution(TestStatus.fail, 600_000);
    assertTrue(policy.applicable());

    policy.registerExecution(TestStatus.fail, 600_000);
    assertFalse(policy.applicable());
    assertEquals(1, totalRetries.get());
  }

  // ---- ExecutionStrategy bucket parsing + telemetry tests ----

  private Config mockConfig(
      boolean dynamicAtrEnabled,
      String dynamicAtrBuckets,
      boolean flakyRetryEnabled) {
    Config config = mock(Config.class);
    when(config.isCiVisibilityDynamicAtrEnabled()).thenReturn(dynamicAtrEnabled);
    when(config.getCiVisibilityDynamicAtrBuckets()).thenReturn(dynamicAtrBuckets);
    when(config.isCiVisibilityFlakyRetryEnabled()).thenReturn(flakyRetryEnabled);
    when(config.getCiVisibilityFlakyRetryCount()).thenReturn(5);
    when(config.getCiVisibilityTotalFlakyRetryCount()).thenReturn(1000);
    return config;
  }

  private ExecutionStrategy newStrategy(
      Config config, ExecutionSettings settings, CiVisibilityMetricCollector collector) {
    return new ExecutionStrategy(
        config,
        settings,
        mock(SourcePathResolver.class),
        mock(LinesResolver.class),
        collector);
  }

  @Test
  void testExecutionStrategyParsesValidBucketsAndRecordsTelemetryWithCustomBuckets() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "3,1,1,1,1", true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertTrue(collector.recordedDynamicAtrWithCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithoutCustomBuckets);
  }

  @Test
  void testExecutionStrategyNoBucketsRecordsTelemetryWithoutCustomBuckets() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, null, true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertTrue(collector.recordedDynamicAtrWithoutCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
  }

  @Test
  void testExecutionStrategyEmptyBucketsRecordsTelemetryWithoutCustomBuckets() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "", true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertTrue(collector.recordedDynamicAtrWithoutCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
  }

  @Test
  void testExecutionStrategyInvalidBucketsFallsBackToNull() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "not,enough,values", true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertTrue(collector.recordedDynamicAtrWithoutCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
  }

  @Test
  void testExecutionStrategyTrailingCommaBucketsFallbackToEfdWithoutCustomBucketTelemetry() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "3,1,1,1,1,", true);
    ExecutionStrategy strategy =
        newStrategy(config, executionSettings(true, efdSettings(1, 2, 3, 4)), collector);

    TestExecutionPolicy policy =
        strategy.executionPolicy(
            new TestIdentifier(new TestFQN("suite", "name"), null),
            TestSourceData.UNKNOWN,
            Collections.emptyList());
    policy.registerExecution(TestStatus.fail, 1_000);
    assertTrue(policy.applicable());
    policy.registerExecution(TestStatus.fail, 1_000);

    assertFalse(policy.applicable());
    assertTrue(collector.recordedDynamicAtrWithoutCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
  }

  @Test
  void testExecutionStrategyOutOfRangeBucketsFallsBackToNull() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "21,4,1,1,1", true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertTrue(collector.recordedDynamicAtrWithoutCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
  }

  @Test
  void testExecutionStrategyZeroValueBucketsFallsBackToNull() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "10,4,0,1,1", true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertTrue(collector.recordedDynamicAtrWithoutCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
  }

  @Test
  void testExecutionStrategyDisabledDoesNotRecordTelemetry() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(false, null, true);
    newStrategy(config, executionSettings(true, efdSettings(10, 2, 3, 4)), collector);

    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithoutCustomBuckets);
  }

  @Test
  void testExecutionStrategyAutoRetryDisabledDoesNotRecordTelemetry() {
    TestMetricCollector collector = new TestMetricCollector();
    Config config = mockConfig(true, "3,1,1,1,1", true);
    // auto retry disabled in backend settings
    newStrategy(config, executionSettings(false, efdSettings(10, 2, 3, 4)), collector);

    assertFalse(collector.recordedDynamicAtrWithCustomBuckets);
    assertFalse(collector.recordedDynamicAtrWithoutCustomBuckets);
  }

  // ---- EarlyFlakeDetectionSettings helper tests ----

  @ParameterizedTest(name = "duration={0}ms -> index={1}")
  @MethodSource("bucketIndexProvider")
  void testRetryBucketIndexForDuration(long durationMillis, int expectedIndex) {
    EarlyFlakeDetectionSettings settings = EarlyFlakeDetectionSettings.DEFAULT;
    assertEquals(expectedIndex, settings.retryBucketIndexForDuration(durationMillis));
  }

  static Stream<Arguments> bucketIndexProvider() {
    return Stream.of(
        arguments(0L, 0),
        arguments(5_000L, 0),
        arguments(5_001L, 1),
        arguments(10_000L, 1),
        arguments(10_001L, 2),
        arguments(30_000L, 2),
        arguments(30_001L, 3),
        arguments(300_000L, 3),
        arguments(300_001L, 4));
  }

  @Test
  void testEfdRetriesForDurationUsesExecutionsByDuration() {
    EarlyFlakeDetectionSettings settings = efdSettings(10, 2, 3, 4);
    assertEquals(10, settings.retriesForDuration(1_000));
    assertEquals(2, settings.retriesForDuration(6_000));
    assertEquals(3, settings.retriesForDuration(20_000));
    assertEquals(4, settings.retriesForDuration(31_000)); // 31s > 30s, falls into 5m bucket
    assertEquals(4, settings.retriesForDuration(300_000)); // exactly 5m
    assertEquals(0, settings.retriesForDuration(301_000)); // >5m, no bucket matches
    assertEquals(0, settings.retriesForDuration(600_000));
  }

  // ---- Test doubles ----

  private static class TestMetricCollector implements CiVisibilityMetricCollector {
    boolean recordedDynamicAtrWithCustomBuckets = false;
    boolean recordedDynamicAtrWithoutCustomBuckets = false;

    @Override
    public void add(CiVisibilityCountMetric metric, long value, TagValue... tags) {
      if (metric == CiVisibilityCountMetric.DYNAMIC_ATR_RETRIES_ENABLED) {
        boolean hasCustomBuckets = false;
        for (TagValue tag : tags) {
          if (tag == HasCustomBuckets.TRUE) {
            hasCustomBuckets = true;
            break;
          }
        }
        if (hasCustomBuckets) {
          recordedDynamicAtrWithCustomBuckets = true;
        } else {
          recordedDynamicAtrWithoutCustomBuckets = true;
        }
      }
    }

    @Override
    public void add(CiVisibilityDistributionMetric metric, int value, TagValue... tags) {}

    @Override
    public void prepareMetrics() {}

    @Override
    public Collection<CiVisibilityMetricData> drain() {
      return Collections.emptyList();
    }
  }
}
