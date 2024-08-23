package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.ipc.Serializer;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
          EarlyFlakeDetectionSettings.DEFAULT,
          null,
          Collections.emptyMap(),
          Collections.emptyMap(),
          Collections.emptyList(),
          null);

  private final boolean itrEnabled;
  private final boolean codeCoverageEnabled;
  private final boolean testSkippingEnabled;
  private final boolean flakyTestRetriesEnabled;
  @Nonnull private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  @Nullable private final String itrCorrelationId;
  @Nonnull private final Map<String, Collection<TestIdentifier>> skippableTestsByModule;
  @Nullable private final Map<String, BitSet> skippableTestsCoverage;
  @Nullable private final Collection<TestIdentifier> flakyTests;
  @Nullable private final Map<String, Collection<TestIdentifier>> knownTestsByModule;

  public ExecutionSettings(
      boolean itrEnabled,
      boolean codeCoverageEnabled,
      boolean testSkippingEnabled,
      boolean flakyTestRetriesEnabled,
      @Nonnull EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      @Nullable String itrCorrelationId,
      @Nonnull Map<String, Collection<TestIdentifier>> skippableTestsByModule,
      @Nullable Map<String, BitSet> skippableTestsCoverage,
      @Nullable Collection<TestIdentifier> flakyTests,
      @Nullable Map<String, Collection<TestIdentifier>> knownTestsByModule) {
    this.itrEnabled = itrEnabled;
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.testSkippingEnabled = testSkippingEnabled;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.itrCorrelationId = itrCorrelationId;
    this.skippableTestsByModule = skippableTestsByModule;
    this.skippableTestsCoverage = skippableTestsCoverage;
    this.flakyTests = flakyTests;
    this.knownTestsByModule = knownTestsByModule;
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

  @Nonnull
  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
  }

  @Nullable
  public String getItrCorrelationId() {
    return itrCorrelationId;
  }

  /** A bit vector of covered lines by relative source file path. */
  @Nullable
  public Map<String, BitSet> getSkippableTestsCoverage() {
    return skippableTestsCoverage;
  }

  @Nonnull
  public Collection<TestIdentifier> getSkippableTests(String moduleName) {
    return skippableTestsByModule.getOrDefault(moduleName, Collections.emptyList());
  }

  /**
   * @return the list of known tests for the given module (can be empty), or {@code null} if known
   *     tests could not be obtained
   */
  @Nullable
  public Collection<TestIdentifier> getKnownTests(String moduleName) {
    return knownTestsByModule != null
        ? knownTestsByModule.getOrDefault(moduleName, Collections.emptyList())
        : null;
  }

  @Nullable
  public Collection<TestIdentifier> getFlakyTests(String moduleName) {
    // backend does not store module info for flaky tests yet
    return flakyTests;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ExecutionSettings that = (ExecutionSettings) o;
    return itrEnabled == that.itrEnabled
        && codeCoverageEnabled == that.codeCoverageEnabled
        && testSkippingEnabled == that.testSkippingEnabled
        && Objects.equals(earlyFlakeDetectionSettings, that.earlyFlakeDetectionSettings)
        && Objects.equals(itrCorrelationId, that.itrCorrelationId)
        && Objects.equals(skippableTestsByModule, that.skippableTestsByModule)
        && Objects.equals(skippableTestsCoverage, that.skippableTestsCoverage)
        && Objects.equals(flakyTests, that.flakyTests)
        && Objects.equals(knownTestsByModule, that.knownTestsByModule);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        itrEnabled,
        codeCoverageEnabled,
        testSkippingEnabled,
        earlyFlakeDetectionSettings,
        itrCorrelationId,
        skippableTestsByModule,
        skippableTestsCoverage,
        flakyTests,
        knownTestsByModule);
  }

  public static class ExecutionSettingsSerializer {

    private static final int ITR_ENABLED_FLAG = 1;
    private static final int CODE_COVERAGE_ENABLED_FLAG = 2;
    private static final int TEST_SKIPPING_ENABLED_FLAG = 4;
    private static final int FLAKY_TEST_RETRIES_ENABLED_FLAG = 8;

    public static ByteBuffer serialize(ExecutionSettings settings) {
      Serializer s = new Serializer();

      byte flags =
          (byte)
              ((settings.itrEnabled ? ITR_ENABLED_FLAG : 0)
                  | (settings.codeCoverageEnabled ? CODE_COVERAGE_ENABLED_FLAG : 0)
                  | (settings.testSkippingEnabled ? TEST_SKIPPING_ENABLED_FLAG : 0)
                  | (settings.flakyTestRetriesEnabled ? FLAKY_TEST_RETRIES_ENABLED_FLAG : 0));
      s.write(flags);

      EarlyFlakeDetectionSettingsSerializer.serialize(s, settings.earlyFlakeDetectionSettings);

      s.write(settings.itrCorrelationId);
      s.write(
          settings.skippableTestsByModule,
          Serializer::write,
          (sr, c) -> sr.write(c, TestIdentifierSerializer::serialize));
      s.write(
          settings.skippableTestsCoverage,
          Serializer::write,
          ExecutionSettingsSerializer::writeBitSet);
      s.write(settings.flakyTests, TestIdentifierSerializer::serialize);
      s.write(
          settings.knownTestsByModule,
          Serializer::write,
          (sr, c) -> sr.write(c, TestIdentifierSerializer::serialize));

      return s.flush();
    }

    public static ExecutionSettings deserialize(ByteBuffer buffer) {
      byte flags = Serializer.readByte(buffer);
      boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
      boolean codeCoverageEnabled = (flags & CODE_COVERAGE_ENABLED_FLAG) != 0;
      boolean testSkippingEnabled = (flags & TEST_SKIPPING_ENABLED_FLAG) != 0;
      boolean flakyTestRetriesEnabled = (flags & FLAKY_TEST_RETRIES_ENABLED_FLAG) != 0;

      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
          EarlyFlakeDetectionSettingsSerializer.deserialize(buffer);

      String itrCorrelationId = Serializer.readString(buffer);
      Map<String, Collection<TestIdentifier>> skippableTestsByModule =
          Serializer.readMap(
              buffer,
              Serializer::readString,
              b -> Serializer.readSet(b, TestIdentifierSerializer::deserialize));
      Map<String, BitSet> skippableTestsCoverage =
          Serializer.readMap(
              buffer, Serializer::readString, ExecutionSettingsSerializer::readBitSet);
      List<TestIdentifier> flakyTests =
          Serializer.readList(buffer, TestIdentifierSerializer::deserialize);
      Map<String, Collection<TestIdentifier>> knownTestsByModule =
          Serializer.readMap(
              buffer,
              Serializer::readString,
              b -> Serializer.readList(b, TestIdentifierSerializer::deserialize));

      return new ExecutionSettings(
          itrEnabled,
          codeCoverageEnabled,
          testSkippingEnabled,
          flakyTestRetriesEnabled,
          earlyFlakeDetectionSettings,
          itrCorrelationId,
          skippableTestsByModule,
          skippableTestsCoverage,
          flakyTests,
          knownTestsByModule);
    }

    private static void writeBitSet(Serializer serializer, BitSet bitSet) {
      if (bitSet != null) {
        serializer.write(bitSet.toByteArray());
      } else {
        serializer.write((byte[]) null);
      }
    }

    private static BitSet readBitSet(ByteBuffer byteBuffer) {
      byte[] bytes = Serializer.readByteArray(byteBuffer);
      return bytes != null ? BitSet.valueOf(bytes) : null;
    }
  }
}
