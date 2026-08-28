package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
