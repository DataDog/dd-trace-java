package datadog.trace.civisibility.config;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    assertEquals(6, settings.executionsForDuration(5_000));
    assertEquals(5, settings.executionsForDuration(5_001));
    assertEquals(4, settings.executionsForDuration(10_001));
    assertEquals(3, settings.executionsForDuration(30_001));
    assertEquals(2, settings.executionsForDuration(300_001));
  }

  @Test
  void normalizesBackendRetriesToExecutions() {
    DynamicAutoTestRetrySettings settings =
        DynamicAutoTestRetrySettings.create(true, null, BACKEND_RETRIES);

    assertFalse(settings.isCustom());
    assertEquals(11, settings.executionsForDuration(5_000));
    assertEquals(3, settings.executionsForDuration(5_001));
    assertEquals(4, settings.executionsForDuration(10_001));
    assertEquals(5, settings.executionsForDuration(30_001));
    assertEquals(2, settings.executionsForDuration(300_001));
    assertEquals(10, BACKEND_RETRIES.get(0).getExecutions());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1})
  void guaranteesAtLeastOneRetry(int retries) {
    DynamicAutoTestRetrySettings settings =
        DynamicAutoTestRetrySettings.create(
            true, null, singletonList(new ExecutionsByDuration(5_000, retries)));

    assertEquals(2, settings.executionsForDuration(1_000));
  }

  @Test
  void retriesOnceWithoutBackendBuckets() {
    DynamicAutoTestRetrySettings settings =
        DynamicAutoTestRetrySettings.create(true, null, emptyList());

    assertEquals(2, settings.executionsForDuration(1_000));
  }

  @Test
  void disabledSettingsIgnoreCustomBuckets() {
    assertSame(
        DynamicAutoTestRetrySettings.DEFAULT,
        DynamicAutoTestRetrySettings.create(false, Arrays.asList(5, 4, 3, 2, 1), BACKEND_RETRIES));
  }

  @Test
  void serializationPreservesExecutionBudgets() {
    DynamicAutoTestRetrySettings settings =
        DynamicAutoTestRetrySettings.create(true, null, BACKEND_RETRIES);
    Serializer serializer = new Serializer();
    DynamicAutoTestRetrySettings.Serializer.serialize(serializer, settings);
    ByteBuffer serialized = serializer.flush();

    DynamicAutoTestRetrySettings deserialized =
        DynamicAutoTestRetrySettings.Serializer.deserialize(serialized);
    assertEquals(settings, deserialized);
    assertEquals(11, deserialized.executionsForDuration(1_000));
    assertEquals(2, deserialized.executionsForDuration(300_001));
  }
}
