package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class InstrumenterModuleTest {

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
  @WithConfig(key = "trace.test-kafka-module.enabled", value = "false")
  @WithConfig(key = "data.streams.enabled", value = "true")
  void testDataStreamsIsEnabledHonorsPerIntegrationDisableEvenWithDsmEnabled() {
    // Users must still be able to disable this integration individually (e.g. for triage)
    // even when DSM is enabled globally.
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    assertFalse(module.isEnabled());
  }

  @Test
  @WithConfig(key = "trace.test-kafka-module.enabled", value = "true")
  @WithConfig(key = "data.streams.enabled", value = "false")
  void testDataStreamsIsEnabledWhenSuperEnabledIsTrue() {
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    assertTrue(module.isEnabled());
  }

  @Test
  @WithConfig(key = "trace.test-kafka-module.enabled", value = "false")
  @WithConfig(key = "data.streams.enabled", value = "false")
  void testDataStreamsIsEnabledWhenBothDisabled() {
    InstrumenterModule.DataStreams module =
        new InstrumenterModule.DataStreams("test-kafka-module") {};

    assertFalse(module.isEnabled());
  }
}
