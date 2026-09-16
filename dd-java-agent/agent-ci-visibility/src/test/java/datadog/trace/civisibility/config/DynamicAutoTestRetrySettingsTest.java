package datadog.trace.civisibility.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.civisibility.diff.LineDiff;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicAutoTestRetrySettingsTest {

  private static final List<ExecutionsByDuration> BACKEND_RETRIES =
      Arrays.asList(
          new ExecutionsByDuration(5_000, 10),
          new ExecutionsByDuration(10_000, 2),
          new ExecutionsByDuration(30_000, 3),
          new ExecutionsByDuration(300_000, 4));

  @Test
  void usesCustomBuckets() {
    DynamicAutoTestRetrySettings settings =
        DynamicAutoTestRetrySettings.create(true, Arrays.asList(5, 4, 3, 2, 1), BACKEND_RETRIES);

    assertTrue(settings.isEnabled());
    assertTrue(settings.isCustom());
    assertEquals(5, settings.retriesForDuration(5_000));
    assertEquals(4, settings.retriesForDuration(5_001));
    assertEquals(3, settings.retriesForDuration(10_001));
    assertEquals(2, settings.retriesForDuration(30_001));
    assertEquals(1, settings.retriesForDuration(300_001));
  }

  @Test
  void disabledSettingsIgnoreCustomBuckets() {
    assertSame(
        DynamicAutoTestRetrySettings.DEFAULT,
        DynamicAutoTestRetrySettings.create(false, Arrays.asList(5, 4, 3, 2, 1), BACKEND_RETRIES));
  }

  @Test
  void serializationPreservesBackendRetries() {
    DynamicAutoTestRetrySettings settings =
        DynamicAutoTestRetrySettings.create(true, null, BACKEND_RETRIES);
    Serializer serializer = new Serializer();
    DynamicAutoTestRetrySettings.Serializer.serialize(serializer, settings);
    ByteBuffer serialized = serializer.flush();

    assertEquals(settings, DynamicAutoTestRetrySettings.Serializer.deserialize(serialized));
  }

  @Test
  void backendRetriesSurviveDisabledEfdAndExecutionSettingsSerialization() {
    EarlyFlakeDetectionSettings backendEfdSettings =
        new EarlyFlakeDetectionSettings(false, BACKEND_RETRIES, -1);
    CiVisibilitySettings backendSettings =
        new CiVisibilitySettings(
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            backendEfdSettings,
            TestManagementSettings.DEFAULT,
            null,
            false);
    Config config = mock(Config.class);
    when(config.isCiVisibilityDynamicAtrEnabled()).thenReturn(true);
    when(config.getCiVisibilityDynamicAtrBuckets()).thenReturn(null);

    DynamicAutoTestRetrySettings dynamicAtrSettings =
        ExecutionSettingsFactoryImpl.createDynamicAutoTestRetrySettings(
            config, backendSettings, true);
    ExecutionSettings executionSettings =
        new ExecutionSettings(
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            EarlyFlakeDetectionSettings.DEFAULT,
            dynamicAtrSettings,
            TestManagementSettings.DEFAULT,
            null,
            Collections.emptyMap(),
            Collections.emptyMap(),
            null,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            LineDiff.EMPTY,
            ConfigurationErrors.NONE);

    ExecutionSettings deserialized =
        ExecutionSettings.Serializer.deserialize(
            ExecutionSettings.Serializer.serialize(executionSettings));

    assertFalse(deserialized.getEarlyFlakeDetectionSettings().isEnabled());
    assertTrue(deserialized.getDynamicAutoTestRetrySettings().isEnabled());
    assertEquals(10, deserialized.getDynamicAutoTestRetrySettings().retriesForDuration(1_000));
  }
}
