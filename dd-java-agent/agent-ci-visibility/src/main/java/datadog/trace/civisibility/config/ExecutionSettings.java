package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.civisibility.diff.Diff;
import datadog.trace.civisibility.diff.LineDiff;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import datadog.trace.util.HashingUtils;

/** Settings and tests data received from the backend. */
public class ExecutionSettings {

  public static final ExecutionSettings EMPTY =
      new ExecutionSettings(
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          EarlyFlakeDetectionSettings.DEFAULT,
          TestManagementSettings.DEFAULT,
          null,
          Collections.emptyMap(),
          Collections.emptyMap(),
          null,
          null,
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          LineDiff.EMPTY);

  private final boolean itrEnabled;
  private final boolean codeCoverageEnabled;
  private final boolean testSkippingEnabled;
  private final boolean flakyTestRetriesEnabled;
  private final boolean impactedTestsDetectionEnabled;
  private final boolean codeCoverageReportUploadEnabled;
  private final boolean failedTestReplayEnabled;
  @Nonnull private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  @Nonnull private final TestManagementSettings testManagementSettings;
  @Nullable private final String itrCorrelationId;
  @Nonnull private final Map<TestIdentifier, TestMetadata> skippableTests;
  @Nonnull private final Map<String, BitSet> skippableTestsCoverage;
  @Nonnull private final Map<TestFQN, Integer> testSettings;
  @Nonnull private final Map<TestSetting, Integer> settingsCount;
  @Nonnull private final Diff pullRequestDiff;

  public ExecutionSettings(
      boolean itrEnabled,
      boolean codeCoverageEnabled,
      boolean testSkippingEnabled,
      boolean flakyTestRetriesEnabled,
      boolean impactedTestsDetectionEnabled,
      boolean codeCoverageReportUploadEnabled,
      boolean failedTestReplayEnabled,
      @Nonnull EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      @Nonnull TestManagementSettings testManagementSettings,
      @Nullable String itrCorrelationId,
      @Nonnull Map<TestIdentifier, TestMetadata> skippableTests,
      @Nonnull Map<String, BitSet> skippableTestsCoverage,
      @Nullable Collection<TestFQN> flakyTests,
      @Nullable Collection<TestFQN> knownTests,
      @Nonnull Collection<TestFQN> quarantinedTests,
      @Nonnull Collection<TestFQN> disabledTests,
      @Nonnull Collection<TestFQN> attemptToFixTests,
      @Nonnull Diff pullRequestDiff) {
    this.itrEnabled = itrEnabled;
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.testSkippingEnabled = testSkippingEnabled;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.impactedTestsDetectionEnabled = impactedTestsDetectionEnabled;
    this.codeCoverageReportUploadEnabled = codeCoverageReportUploadEnabled;
    this.failedTestReplayEnabled = failedTestReplayEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.testManagementSettings = testManagementSettings;
    this.itrCorrelationId = itrCorrelationId;
    this.skippableTests = skippableTests;
    this.skippableTestsCoverage = skippableTestsCoverage;
    this.pullRequestDiff = pullRequestDiff;

    testSettings = new HashMap<>();
    if (flakyTests != null) {
      flakyTests.forEach(fqn -> addTest(fqn, TestSetting.FLAKY));
    }
    if (knownTests != null) {
      knownTests.forEach(fqn -> addTest(fqn, TestSetting.KNOWN));
    }
    quarantinedTests.forEach(fqn -> addTest(fqn, TestSetting.QUARANTINED));
    disabledTests.forEach(fqn -> addTest(fqn, TestSetting.DISABLED));
    attemptToFixTests.forEach(fqn -> addTest(fqn, TestSetting.ATTEMPT_TO_FIX));

    settingsCount = new EnumMap<>(TestSetting.class);
    settingsCount.put(TestSetting.FLAKY, flakyTests != null ? flakyTests.size() : -1);
    settingsCount.put(TestSetting.KNOWN, knownTests != null ? knownTests.size() : -1);
    settingsCount.put(TestSetting.QUARANTINED, quarantinedTests.size());
    settingsCount.put(TestSetting.DISABLED, disabledTests.size());
    settingsCount.put(TestSetting.ATTEMPT_TO_FIX, attemptToFixTests.size());
  }

  private void addTest(TestFQN test, TestSetting setting) {
    testSettings.merge(test, setting.getFlag(), (a, b) -> a | b);
  }

  private ExecutionSettings(
      boolean itrEnabled,
      boolean codeCoverageEnabled,
      boolean testSkippingEnabled,
      boolean flakyTestRetriesEnabled,
      boolean impactedTestsDetectionEnabled,
      boolean codeCoverageReportUploadEnabled,
      boolean failedTestReplayEnabled,
      @Nonnull EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      @Nonnull TestManagementSettings testManagementSettings,
      @Nullable String itrCorrelationId,
      @Nonnull Map<TestIdentifier, TestMetadata> skippableTests,
      @Nonnull Map<String, BitSet> skippableTestsCoverage,
      @Nonnull Map<TestFQN, Integer> testSettings,
      @Nonnull EnumMap<TestSetting, Integer> settingsCount,
      @Nonnull Diff pullRequestDiff) {
    this.itrEnabled = itrEnabled;
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.testSkippingEnabled = testSkippingEnabled;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.impactedTestsDetectionEnabled = impactedTestsDetectionEnabled;
    this.codeCoverageReportUploadEnabled = codeCoverageReportUploadEnabled;
    this.failedTestReplayEnabled = failedTestReplayEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.testManagementSettings = testManagementSettings;
    this.itrCorrelationId = itrCorrelationId;
    this.skippableTests = skippableTests;
    this.skippableTestsCoverage = skippableTestsCoverage;
    this.testSettings = testSettings;
    this.settingsCount = settingsCount;
    this.pullRequestDiff = pullRequestDiff;
  }

  /**
   * @return {@code true} if ITR is enabled. Enabled ITR does not necessarily imply test skipping:
   *     for an excluded branch ITR will be enabled, but not skipping.
   */
  public boolean isItrEnabled() {
    return itrEnabled;
  }

  public boolean isCodeCoverageEnabled() {
    return codeCoverageEnabled;
  }

  public boolean isTestSkippingEnabled() {
    return testSkippingEnabled;
  }

  public boolean isFlakyTestRetriesEnabled() {
    return flakyTestRetriesEnabled;
  }

  public boolean isImpactedTestsDetectionEnabled() {
    return impactedTestsDetectionEnabled;
  }

  public boolean isCodeCoverageReportUploadEnabled() {
    return codeCoverageReportUploadEnabled;
  }

  public boolean isFailedTestReplayEnabled() {
    return failedTestReplayEnabled;
  }

  @Nonnull
  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
  }

  @Nonnull
  public TestManagementSettings getTestManagementSettings() {
    return testManagementSettings;
  }

  @Nullable
  public String getItrCorrelationId() {
    return itrCorrelationId;
  }

  @Nonnull
  public Map<TestIdentifier, TestMetadata> getSkippableTests() {
    return skippableTests;
  }

  /** A bit vector of covered lines by relative source file path. */
  @Nonnull
  public Map<String, BitSet> getSkippableTestsCoverage() {
    return skippableTestsCoverage;
  }

  public boolean isFlakyTestsDataAvailable() {
    return getSettingCount(TestSetting.FLAKY) != -1;
  }

  public boolean isKnownTestsDataAvailable() {
    return getSettingCount(TestSetting.KNOWN) != -1;
  }

  private boolean isSetting(TestFQN test, TestSetting setting) {
    int mask = testSettings.getOrDefault(test, 0);
    return TestSetting.isSet(mask, setting);
  }

  public boolean isFlaky(TestFQN test) {
    return isSetting(test, TestSetting.FLAKY);
  }

  public boolean isKnown(TestFQN test) {
    return isSetting(test, TestSetting.KNOWN);
  }

  public boolean isQuarantined(TestFQN test) {
    return isSetting(test, TestSetting.QUARANTINED);
  }

  public boolean isDisabled(TestFQN test) {
    return isSetting(test, TestSetting.DISABLED);
  }

  public boolean isAttemptToFix(TestFQN test) {
    return isSetting(test, TestSetting.ATTEMPT_TO_FIX);
  }

  /**
   * @return number of tests with a certain setting "activated". For flaky and known test, it will
   *     return -1 if a null value was provided to the constructor.
   */
  public int getSettingCount(TestSetting setting) {
    return settingsCount.getOrDefault(setting, 0);
  }

  @Nonnull
  public Diff getPullRequestDiff() {
    return pullRequestDiff;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ExecutionSettings that = (ExecutionSettings) o;
    return itrEnabled == that.itrEnabled
        && codeCoverageEnabled == that.codeCoverageEnabled
        && testSkippingEnabled == that.testSkippingEnabled
        && flakyTestRetriesEnabled == that.flakyTestRetriesEnabled
        && impactedTestsDetectionEnabled == that.impactedTestsDetectionEnabled
        && codeCoverageReportUploadEnabled == that.codeCoverageReportUploadEnabled
        && failedTestReplayEnabled == that.failedTestReplayEnabled
        && Objects.equals(earlyFlakeDetectionSettings, that.earlyFlakeDetectionSettings)
        && Objects.equals(testManagementSettings, that.testManagementSettings)
        && Objects.equals(itrCorrelationId, that.itrCorrelationId)
        && Objects.equals(skippableTests, that.skippableTests)
        && Objects.equals(skippableTestsCoverage, that.skippableTestsCoverage)
        && Objects.equals(testSettings, that.testSettings)
        && Objects.equals(settingsCount, that.settingsCount)
        && Objects.equals(pullRequestDiff, that.pullRequestDiff);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(
        itrEnabled,
        codeCoverageEnabled,
        testSkippingEnabled,
        flakyTestRetriesEnabled,
        impactedTestsDetectionEnabled,
        codeCoverageReportUploadEnabled,
        failedTestReplayEnabled,
        earlyFlakeDetectionSettings,
        testManagementSettings,
        itrCorrelationId,
        skippableTests,
        skippableTestsCoverage,
        testSettings,
        settingsCount,
        pullRequestDiff);
  }

  public static class Serializer {

    private static final int ITR_ENABLED_FLAG = 1;
    private static final int CODE_COVERAGE_ENABLED_FLAG = 2;
    private static final int TEST_SKIPPING_ENABLED_FLAG = 4;
    private static final int FLAKY_TEST_RETRIES_ENABLED_FLAG = 8;
    private static final int IMPACTED_TESTS_DETECTION_ENABLED_FLAG = 16;
    private static final int CODE_COVERAGE_REPORT_UPLOAD_ENABLED_FLAG = 32;
    private static final int FAILED_TEST_REPLAY_ENABLED_FLAG = 64;

    public static ByteBuffer serialize(ExecutionSettings settings) {
      datadog.trace.civisibility.ipc.serialization.Serializer s =
          new datadog.trace.civisibility.ipc.serialization.Serializer();

      byte flags =
          (byte)
              ((settings.itrEnabled ? ITR_ENABLED_FLAG : 0)
                  | (settings.codeCoverageEnabled ? CODE_COVERAGE_ENABLED_FLAG : 0)
                  | (settings.testSkippingEnabled ? TEST_SKIPPING_ENABLED_FLAG : 0)
                  | (settings.flakyTestRetriesEnabled ? FLAKY_TEST_RETRIES_ENABLED_FLAG : 0)
                  | (settings.impactedTestsDetectionEnabled
                      ? IMPACTED_TESTS_DETECTION_ENABLED_FLAG
                      : 0)
                  | (settings.codeCoverageReportUploadEnabled
                      ? CODE_COVERAGE_REPORT_UPLOAD_ENABLED_FLAG
                      : 0)
                  | (settings.failedTestReplayEnabled ? FAILED_TEST_REPLAY_ENABLED_FLAG : 0));
      s.write(flags);

      EarlyFlakeDetectionSettings.Serializer.serialize(s, settings.earlyFlakeDetectionSettings);

      TestManagementSettings.Serializer.serialize(s, settings.testManagementSettings);

      s.write(settings.itrCorrelationId);
      s.write(
          settings.skippableTests,
          TestIdentifierSerializer::serialize,
          TestMetadataSerializer::serialize);

      s.write(
          settings.skippableTestsCoverage,
          datadog.trace.civisibility.ipc.serialization.Serializer::write,
          datadog.trace.civisibility.ipc.serialization.Serializer::write);

      s.write(
          settings.testSettings,
          TestFQNSerializer::serialize,
          datadog.trace.civisibility.ipc.serialization.Serializer::write);
      s.write(
          settings.settingsCount,
          TestSetting.Serializer::serialize,
          datadog.trace.civisibility.ipc.serialization.Serializer::write);

      Diff.SERIALIZER.serialize(settings.pullRequestDiff, s);

      return s.flush();
    }

    public static ExecutionSettings deserialize(ByteBuffer buffer) {
      byte flags = datadog.trace.civisibility.ipc.serialization.Serializer.readByte(buffer);
      boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
      boolean codeCoverageEnabled = (flags & CODE_COVERAGE_ENABLED_FLAG) != 0;
      boolean testSkippingEnabled = (flags & TEST_SKIPPING_ENABLED_FLAG) != 0;
      boolean flakyTestRetriesEnabled = (flags & FLAKY_TEST_RETRIES_ENABLED_FLAG) != 0;
      boolean impactedTestsDetectionEnabled = (flags & IMPACTED_TESTS_DETECTION_ENABLED_FLAG) != 0;
      boolean codeCoverageReportUploadEnabled =
          (flags & CODE_COVERAGE_REPORT_UPLOAD_ENABLED_FLAG) != 0;
      boolean failedTestReplayEnabled = (flags & FAILED_TEST_REPLAY_ENABLED_FLAG) != 0;

      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
          EarlyFlakeDetectionSettings.Serializer.deserialize(buffer);

      TestManagementSettings testManagementSettings =
          TestManagementSettings.Serializer.deserialize(buffer);

      String itrCorrelationId =
          datadog.trace.civisibility.ipc.serialization.Serializer.readString(buffer);

      Map<TestIdentifier, TestMetadata> skippableTests =
          datadog.trace.civisibility.ipc.serialization.Serializer.readMap(
              buffer, TestIdentifierSerializer::deserialize, TestMetadataSerializer::deserialize);

      Map<String, BitSet> skippableTestsCoverage =
          datadog.trace.civisibility.ipc.serialization.Serializer.readMap(
              buffer,
              datadog.trace.civisibility.ipc.serialization.Serializer::readString,
              datadog.trace.civisibility.ipc.serialization.Serializer::readBitSet);

      Map<TestFQN, Integer> testSettings =
          datadog.trace.civisibility.ipc.serialization.Serializer.readMap(
              buffer,
              TestFQNSerializer::deserialize,
              datadog.trace.civisibility.ipc.serialization.Serializer::readInt);

      EnumMap<TestSetting, Integer> settingsCount =
          (EnumMap<TestSetting, Integer>)
              datadog.trace.civisibility.ipc.serialization.Serializer.readMap(
                  buffer,
                  () -> new EnumMap<>(TestSetting.class),
                  TestSetting.Serializer::deserialize,
                  datadog.trace.civisibility.ipc.serialization.Serializer::readInt);

      Diff diff = Diff.SERIALIZER.deserialize(buffer);

      return new ExecutionSettings(
          itrEnabled,
          codeCoverageEnabled,
          testSkippingEnabled,
          flakyTestRetriesEnabled,
          impactedTestsDetectionEnabled,
          codeCoverageReportUploadEnabled,
          failedTestReplayEnabled,
          earlyFlakeDetectionSettings,
          testManagementSettings,
          itrCorrelationId,
          skippableTests,
          skippableTestsCoverage,
          testSettings,
          settingsCount,
          diff);
    }
  }
}
