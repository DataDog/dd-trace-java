package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.civisibility.diff.Diff;
import datadog.trace.civisibility.diff.LineDiff;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Settings and tests data received from the backend. */
public class ExecutionSettings {

  public static final ExecutionSettings EMPTY =
      new ExecutionSettings(
          false,
          false,
          false,
          false,
          false,
          EarlyFlakeDetectionSettings.DEFAULT,
          null,
          Collections.emptyMap(),
          Collections.emptyMap(),
          Collections.emptyList(),
          null,
          LineDiff.EMPTY);

  private final boolean itrEnabled;
  private final boolean codeCoverageEnabled;
  private final boolean testSkippingEnabled;
  private final boolean flakyTestRetriesEnabled;
  private final boolean impactedTestsDetectionEnabled;
  @Nonnull private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  @Nullable private final String itrCorrelationId;
  @Nonnull private final Map<TestIdentifier, TestMetadata> skippableTests;
  @Nonnull private final Map<String, BitSet> skippableTestsCoverage;
  @Nullable private final Collection<TestIdentifier> flakyTests;
  @Nullable private final Collection<TestIdentifier> knownTests;
  @Nonnull private final Diff pullRequestDiff;

  public ExecutionSettings(
      boolean itrEnabled,
      boolean codeCoverageEnabled,
      boolean testSkippingEnabled,
      boolean flakyTestRetriesEnabled,
      boolean impactedTestsDetectionEnabled,
      @Nonnull EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      @Nullable String itrCorrelationId,
      @Nonnull Map<TestIdentifier, TestMetadata> skippableTests,
      @Nonnull Map<String, BitSet> skippableTestsCoverage,
      @Nullable Collection<TestIdentifier> flakyTests,
      @Nullable Collection<TestIdentifier> knownTests,
      @Nonnull Diff pullRequestDiff) {
    this.itrEnabled = itrEnabled;
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.testSkippingEnabled = testSkippingEnabled;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.impactedTestsDetectionEnabled = impactedTestsDetectionEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.itrCorrelationId = itrCorrelationId;
    this.skippableTests = skippableTests;
    this.skippableTestsCoverage = skippableTestsCoverage;
    this.flakyTests = flakyTests;
    this.knownTests = knownTests;
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

  @Nonnull
  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
  }

  @Nullable
  public String getItrCorrelationId() {
    return itrCorrelationId;
  }

  /** A bit vector of covered lines by relative source file path. */
  @Nonnull
  public Map<String, BitSet> getSkippableTestsCoverage() {
    return skippableTestsCoverage;
  }

  @Nonnull
  public Map<TestIdentifier, TestMetadata> getSkippableTests() {
    return skippableTests;
  }

  /**
   * @return the list of known tests for the given module (can be empty), or {@code null} if known
   *     tests could not be obtained
   */
  @Nullable
  public Collection<TestIdentifier> getKnownTests() {
    return knownTests;
  }

  /**
   * @return the list of flaky tests for the given module (can be empty), or {@code null} if flaky
   *     tests could not be obtained
   */
  @Nullable
  public Collection<TestIdentifier> getFlakyTests() {
    return flakyTests;
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
        && Objects.equals(earlyFlakeDetectionSettings, that.earlyFlakeDetectionSettings)
        && Objects.equals(itrCorrelationId, that.itrCorrelationId)
        && Objects.equals(skippableTests, that.skippableTests)
        && Objects.equals(skippableTestsCoverage, that.skippableTestsCoverage)
        && Objects.equals(flakyTests, that.flakyTests)
        && Objects.equals(knownTests, that.knownTests)
        && Objects.equals(pullRequestDiff, that.pullRequestDiff);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        itrEnabled,
        codeCoverageEnabled,
        testSkippingEnabled,
        earlyFlakeDetectionSettings,
        itrCorrelationId,
        skippableTests,
        skippableTestsCoverage,
        flakyTests,
        knownTests,
        pullRequestDiff);
  }

  public static class ExecutionSettingsSerializer {

    private static final int ITR_ENABLED_FLAG = 1;
    private static final int CODE_COVERAGE_ENABLED_FLAG = 2;
    private static final int TEST_SKIPPING_ENABLED_FLAG = 4;
    private static final int FLAKY_TEST_RETRIES_ENABLED_FLAG = 8;
    private static final int IMPACTED_TESTS_DETECTION_ENABLED_FLAG = 16;

    public static ByteBuffer serialize(ExecutionSettings settings) {
      Serializer s = new Serializer();

      byte flags =
          (byte)
              ((settings.itrEnabled ? ITR_ENABLED_FLAG : 0)
                  | (settings.codeCoverageEnabled ? CODE_COVERAGE_ENABLED_FLAG : 0)
                  | (settings.testSkippingEnabled ? TEST_SKIPPING_ENABLED_FLAG : 0)
                  | (settings.flakyTestRetriesEnabled ? FLAKY_TEST_RETRIES_ENABLED_FLAG : 0)
                  | (settings.impactedTestsDetectionEnabled
                      ? IMPACTED_TESTS_DETECTION_ENABLED_FLAG
                      : 0));
      s.write(flags);

      EarlyFlakeDetectionSettingsSerializer.serialize(s, settings.earlyFlakeDetectionSettings);

      s.write(settings.itrCorrelationId);
      s.write(
          settings.skippableTests,
          TestIdentifierSerializer::serialize,
          TestMetadataSerializer::serialize);

      s.write(settings.skippableTestsCoverage, Serializer::write, Serializer::write);
      s.write(settings.flakyTests, TestIdentifierSerializer::serialize);
      s.write(settings.knownTests, TestIdentifierSerializer::serialize);

      Diff.SERIALIZER.serialize(settings.pullRequestDiff, s);

      return s.flush();
    }

    public static ExecutionSettings deserialize(ByteBuffer buffer) {
      byte flags = Serializer.readByte(buffer);
      boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
      boolean codeCoverageEnabled = (flags & CODE_COVERAGE_ENABLED_FLAG) != 0;
      boolean testSkippingEnabled = (flags & TEST_SKIPPING_ENABLED_FLAG) != 0;
      boolean flakyTestRetriesEnabled = (flags & FLAKY_TEST_RETRIES_ENABLED_FLAG) != 0;
      boolean impactedTestsDetectionEnabled = (flags & IMPACTED_TESTS_DETECTION_ENABLED_FLAG) != 0;

      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
          EarlyFlakeDetectionSettingsSerializer.deserialize(buffer);

      String itrCorrelationId = Serializer.readString(buffer);

      Map<TestIdentifier, TestMetadata> skippableTests =
          Serializer.readMap(
              buffer, TestIdentifierSerializer::deserialize, TestMetadataSerializer::deserialize);

      Map<String, BitSet> skippableTestsCoverage =
          Serializer.readMap(buffer, Serializer::readString, Serializer::readBitSet);
      Collection<TestIdentifier> flakyTests =
          Serializer.readSet(buffer, TestIdentifierSerializer::deserialize);
      Collection<TestIdentifier> knownTests =
          Serializer.readSet(buffer, TestIdentifierSerializer::deserialize);

      Diff diff = Diff.SERIALIZER.deserialize(buffer);

      return new ExecutionSettings(
          itrEnabled,
          codeCoverageEnabled,
          testSkippingEnabled,
          flakyTestRetriesEnabled,
          impactedTestsDetectionEnabled,
          earlyFlakeDetectionSettings,
          itrCorrelationId,
          skippableTests,
          skippableTestsCoverage,
          flakyTests,
          knownTests,
          diff);
    }
  }
}
