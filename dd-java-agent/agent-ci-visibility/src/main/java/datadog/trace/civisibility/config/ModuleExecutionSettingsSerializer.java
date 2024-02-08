package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleExecutionSettingsSerializer {

  public static ByteBuffer serialize(ModuleExecutionSettings settings) {
    int skippableTestsSize = Integer.BYTES; // entry count
    Map<String, Collection<TestIdentifier>> skippableTestsByModule =
        settings.getSkippableTestsByModule();
    Map<String, ByteBuffer> serializedSkippableTestsByModule =
        new HashMap<>(skippableTestsByModule.size() * 4 / 3);
    for (Map.Entry<String, Collection<TestIdentifier>> e : skippableTestsByModule.entrySet()) {
      String moduleName = e.getKey();
      skippableTestsSize += serializeStringLength(moduleName.getBytes());

      ByteBuffer serializedTests = TestIdentifierSerializer.serialize(e.getValue());
      skippableTestsSize += serializedTests.remaining();

      serializedSkippableTestsByModule.put(moduleName, serializedTests);
    }

    Collection<TestIdentifier> flakyTests = settings.getFlakyTests();
    ByteBuffer serializedFlakyTests = TestIdentifierSerializer.serialize(flakyTests);
    int flakyTestsSize = serializedFlakyTests.remaining();

    Map<String, String> systemProperties = settings.getSystemProperties();
    int systemPropertiesSize = Integer.BYTES; // entry count
    for (Map.Entry<String, String> e : systemProperties.entrySet()) {
      String key = e.getKey();
      if (key != null) {
        systemPropertiesSize += serializeStringLength(key.getBytes());
      }
      String value = e.getValue();
      if (value != null) {
        systemPropertiesSize += serializeStringLength(value.getBytes());
      }
    }

    List<String> coverageEnabledPackages = settings.getCoverageEnabledPackages();
    int coveragePackagesSize = Integer.BYTES; // entry count
    for (String coveragePackage : coverageEnabledPackages) {
      coveragePackagesSize += serializeStringLength(coveragePackage.getBytes());
    }

    int size =
        skippableTestsSize
            + flakyTestsSize
            + systemPropertiesSize
            + coveragePackagesSize
            + Byte.BYTES; // flags
    ByteBuffer buffer = ByteBuffer.allocate(size);

    buffer.putInt(serializedSkippableTestsByModule.size());
    for (Map.Entry<String, ByteBuffer> e : serializedSkippableTestsByModule.entrySet()) {
      serializeString(buffer, e.getKey().getBytes());
      buffer.put(e.getValue());
    }

    buffer.put(serializedFlakyTests);

    buffer.putInt(systemProperties.size());
    for (Map.Entry<String, String> e : systemProperties.entrySet()) {
      serializeString(buffer, e.getKey().getBytes());
      serializeString(buffer, e.getValue().getBytes());
    }

    buffer.putInt(coverageEnabledPackages.size());
    for (String coverageEnabledPackage : coverageEnabledPackages) {
      serializeString(buffer, coverageEnabledPackage.getBytes());
    }

    byte flags =
        (byte)
            ((settings.isCodeCoverageEnabled() ? 1 : 0)
                | (settings.isItrEnabled() ? 2 : 0)
                | (settings.isFlakyTestRetriesEnabled() ? 4 : 0));
    buffer.put(flags);

    buffer.flip();
    return buffer;
  }

  private static int serializeStringLength(byte[] stringBytes) {
    return Integer.BYTES + (stringBytes != null ? stringBytes.length : 0);
  }

  private static void serializeString(ByteBuffer buffer, byte[] stringBytes) {
    if (stringBytes != null) {
      buffer.putInt(stringBytes.length);
      buffer.put(stringBytes);
    } else {
      buffer.putInt(-1);
    }
  }

  public static ModuleExecutionSettings deserialize(ByteBuffer buffer) {
    int skippableTestsByModuleSize = buffer.getInt();
    Map<String, Collection<TestIdentifier>> skippableTestsByModule =
        new HashMap<>(skippableTestsByModuleSize * 4 / 3);
    for (int i = 0; i < skippableTestsByModuleSize; i++) {
      String moduleName = deserializeString(buffer);
      Collection<TestIdentifier> skippableTests = TestIdentifierSerializer.deserialize(buffer);
      skippableTestsByModule.put(moduleName, skippableTests);
    }

    Collection<TestIdentifier> flakyTests = TestIdentifierSerializer.deserialize(buffer);

    int systemPropsSize = buffer.getInt();
    Map<String, String> systemProperties = new HashMap<>(systemPropsSize * 4 / 3);
    for (int i = 0; i < systemPropsSize; i++) {
      String key = deserializeString(buffer);
      String value = deserializeString(buffer);
      systemProperties.put(key, value);
    }

    int coveragePackagesSize = buffer.getInt();
    List<String> codeCoveragePackages = new ArrayList<>(coveragePackagesSize);
    for (int i = 0; i < coveragePackagesSize; i++) {
      codeCoveragePackages.add(deserializeString(buffer));
    }

    byte flags = buffer.get();
    boolean codeCoverageEnabled = (flags & 1) != 0;
    boolean itrEnabled = (flags & 2) != 0;
    boolean flakyTestRetriesEnabled = (flags & 4) != 0;

    return new ModuleExecutionSettings(
        codeCoverageEnabled,
        itrEnabled,
        flakyTestRetriesEnabled,
        systemProperties,
        skippableTestsByModule,
        flakyTests,
        codeCoveragePackages);
  }

  private static String deserializeString(ByteBuffer buffer) {
    int jvmNameBytesLength = buffer.getInt();
    if (jvmNameBytesLength >= 0) {
      byte[] jvmNameBytes = new byte[jvmNameBytesLength];
      buffer.get(jvmNameBytes);
      return new String(jvmNameBytes, StandardCharsets.UTF_8);
    } else {
      return null;
    }
  }
}
