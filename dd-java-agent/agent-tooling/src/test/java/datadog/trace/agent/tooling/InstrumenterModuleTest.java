package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.api.InstrumenterConfig;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InstrumenterModuleTest {

  private static final Field ENABLED_FIELD = getEnabledField();

  private static Field getEnabledField() {
    try {
      Field field = InstrumenterModule.class.getDeclaredField("enabled");
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Test
  void testDataStreamsIsApplicableWithTracing() {
    Set<TargetSystem> enabledSystems = new HashSet<>();
    enabledSystems.add(TargetSystem.TRACING);

    InstrumenterModule.DataStreams module = new InstrumenterModule.DataStreams("test-module") {};

    assertTrue(module.isApplicable(enabledSystems));
  }

  @Test
  void testDataStreamsIsApplicableWithDataStreams() {
    Set<TargetSystem> enabledSystems = new HashSet<>();
    enabledSystems.add(TargetSystem.DATA_STREAMS);

    InstrumenterModule.DataStreams module = new InstrumenterModule.DataStreams("test-module") {};

    assertTrue(module.isApplicable(enabledSystems));
  }

  @Test
  void testDataStreamsIsApplicableWithBoth() {
    Set<TargetSystem> enabledSystems = new HashSet<>();
    enabledSystems.add(TargetSystem.TRACING);
    enabledSystems.add(TargetSystem.DATA_STREAMS);

    InstrumenterModule.DataStreams module = new InstrumenterModule.DataStreams("test-module") {};

    assertTrue(module.isApplicable(enabledSystems));
  }

  @Test
  void testDataStreamsIsApplicableWithNeither() {
    Set<TargetSystem> enabledSystems = new HashSet<>();
    enabledSystems.add(TargetSystem.APPSEC);

    InstrumenterModule.DataStreams module = new InstrumenterModule.DataStreams("test-module") {};

    assertFalse(module.isApplicable(enabledSystems));
  }

  @Test
  void testDataStreamsIsEnabledWhenDataStreamsEnabledOverridesFalse()
      throws IllegalAccessException {
    // When DSM is enabled, isEnabled() should return true even if super.isEnabled() is false
    // This tests the edge case where trace.kafka.enabled=false but DSM is explicitly enabled
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    // Set the enabled field to false to simulate disabled tracing
    ENABLED_FIELD.setBoolean(module, false);

    // Override InstrumenterConfig to return true for isDataStreamsEnabled()
    InstrumenterConfig originalConfig = InstrumenterConfig.get();
    setFieldInConfig(originalConfig, "dataStreamsEnabled", true);

    try {
      assertTrue(module.isEnabled());
    } finally {
      setFieldInConfig(originalConfig, "dataStreamsEnabled", false);
    }
  }

  @Test
  void testDataStreamsIsEnabledWhenSuperEnabledIsTrue() throws IllegalAccessException {
    // When super.isEnabled() is true, isEnabled() should return true regardless of DSM state
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    // Set the enabled field to true to simulate enabled tracing
    ENABLED_FIELD.setBoolean(module, true);

    // Ensure DSM is disabled
    InstrumenterConfig originalConfig = InstrumenterConfig.get();
    boolean originalDataStreamsEnabled = originalConfig.isDataStreamsEnabled();
    setFieldInConfig(originalConfig, "dataStreamsEnabled", false);

    try {
      assertTrue(module.isEnabled());
    } finally {
      setFieldInConfig(originalConfig, "dataStreamsEnabled", originalDataStreamsEnabled);
    }
  }

  @Test
  void testDataStreamsIsEnabledWhenBothEnabled() throws IllegalAccessException {
    // When both super.isEnabled() and DSM are enabled, isEnabled() should return true
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    ENABLED_FIELD.setBoolean(module, true);

    InstrumenterConfig originalConfig = InstrumenterConfig.get();
    boolean originalDataStreamsEnabled = originalConfig.isDataStreamsEnabled();
    setFieldInConfig(originalConfig, "dataStreamsEnabled", true);

    try {
      assertTrue(module.isEnabled());
    } finally {
      setFieldInConfig(originalConfig, "dataStreamsEnabled", originalDataStreamsEnabled);
    }
  }

  @Test
  void testDataStreamsIsEnabledWhenBothDisabled() throws IllegalAccessException {
    // When both super.isEnabled() and DSM are disabled, isEnabled() should return false
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    ENABLED_FIELD.setBoolean(module, false);

    InstrumenterConfig originalConfig = InstrumenterConfig.get();
    boolean originalDataStreamsEnabled = originalConfig.isDataStreamsEnabled();
    setFieldInConfig(originalConfig, "dataStreamsEnabled", false);

    try {
      assertFalse(module.isEnabled());
    } finally {
      setFieldInConfig(originalConfig, "dataStreamsEnabled", originalDataStreamsEnabled);
    }
  }

  private static void setFieldInConfig(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
