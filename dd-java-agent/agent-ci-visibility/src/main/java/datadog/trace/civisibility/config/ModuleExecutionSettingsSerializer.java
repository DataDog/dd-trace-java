package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.ipc.Serializer;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ModuleExecutionSettingsSerializer {

  private static final int CODE_COVERAGE_ENABLED_FLAG = 1;
  private static final int ITR_ENABLED_FLAG = 2;
  private static final int FLAKY_TEST_RETRIES_ENABLED_FLAG = 4;

  public static ByteBuffer serialize(ModuleExecutionSettings settings) {
    Serializer s = new Serializer();

    byte flags =
        (byte)
            ((settings.isCodeCoverageEnabled() ? CODE_COVERAGE_ENABLED_FLAG : 0)
                | (settings.isItrEnabled() ? ITR_ENABLED_FLAG : 0)
                | (settings.isFlakyTestRetriesEnabled() ? FLAKY_TEST_RETRIES_ENABLED_FLAG : 0));
    s.write(flags);

    EarlyFlakeDetectionSettingsSerializer.serialize(s, settings.getEarlyFlakeDetectionSettings());

    s.write(settings.getSystemProperties());
    s.write(settings.getItrCorrelationId());
    s.write(
        settings.getSkippableTestsByModule(),
        Serializer::write,
        (sr, c) -> sr.write(c, TestIdentifierSerializer::serialize));
    s.write(settings.getFlakyTests(), TestIdentifierSerializer::serialize);
    s.write(
        settings.getKnownTestsByModule(),
        Serializer::write,
        (sr, c) -> sr.write(c, TestIdentifierSerializer::serialize));
    s.write(settings.getCoverageEnabledPackages());

    return s.flush();
  }

  public static ModuleExecutionSettings deserialize(ByteBuffer buffer) {
    byte flags = Serializer.readByte(buffer);
    boolean codeCoverageEnabled = (flags & CODE_COVERAGE_ENABLED_FLAG) != 0;
    boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
    boolean flakyTestRetriesEnabled = (flags & FLAKY_TEST_RETRIES_ENABLED_FLAG) != 0;

    EarlyFlakeDetectionSettings earlyFlakeDetectionSettings =
        EarlyFlakeDetectionSettingsSerializer.deserialize(buffer);

    Map<String, String> systemProperties = Serializer.readStringMap(buffer);
    String itrCorrelationId = Serializer.readString(buffer);
    Map<String, Collection<TestIdentifier>> skippableTestsByModule =
        Serializer.readMap(
            buffer,
            Serializer::readString,
            b -> Serializer.readList(b, TestIdentifierSerializer::deserialize));
    List<TestIdentifier> flakyTests =
        Serializer.readList(buffer, TestIdentifierSerializer::deserialize);
    Map<String, Collection<TestIdentifier>> knownTestsByModule =
        Serializer.readMap(
            buffer,
            Serializer::readString,
            b -> Serializer.readList(b, TestIdentifierSerializer::deserialize));
    List<String> codeCoveragePackages = Serializer.readStringList(buffer);

    return new ModuleExecutionSettings(
        codeCoverageEnabled,
        itrEnabled,
        flakyTestRetriesEnabled,
        earlyFlakeDetectionSettings,
        systemProperties,
        itrCorrelationId,
        skippableTestsByModule,
        flakyTests,
        knownTestsByModule,
        codeCoveragePackages);
  }
}
