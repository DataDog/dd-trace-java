package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link AgentInstaller#getEnabledSystems()} to verify that it correctly includes target
 * systems based on their corresponding configuration flags.
 */
@ExtendWith(WithConfigExtension.class)
class AgentInstallerGetEnabledSystemsTest {

  /**
   * Verifies that DATA_STREAMS target system is not included when data.streams.enabled is false
   * (default).
   */
  @Test
  void dataStreamsNotIncludedWhenDisabled() {
    Set<TargetSystem> enabledSystems = AgentInstaller.getEnabledSystems();
    assertFalse(
        enabledSystems.contains(TargetSystem.DATA_STREAMS),
        "DATA_STREAMS should not be included when disabled");
  }

  /** Verifies that DATA_STREAMS target system is included when data.streams.enabled is true. */
  @Test
  @WithConfig(key = "data.streams.enabled", value = "true")
  void dataStreamsIncludedWhenEnabled() {
    Set<TargetSystem> enabledSystems = AgentInstaller.getEnabledSystems();
    assertTrue(
        enabledSystems.contains(TargetSystem.DATA_STREAMS),
        "DATA_STREAMS should be included when enabled");
  }

  /** Verifies that USM target system is not included when usm.enabled is false (default). */
  @Test
  void usmNotIncludedWhenDisabled() {
    Set<TargetSystem> enabledSystems = AgentInstaller.getEnabledSystems();
    assertFalse(
        enabledSystems.contains(TargetSystem.USM), "USM should not be included when disabled");
  }

  /** Verifies that USM target system is included when usm.enabled is true. */
  @Test
  @WithConfig(key = "usm.enabled", value = "true")
  void usmIncludedWhenEnabled() {
    Set<TargetSystem> enabledSystems = AgentInstaller.getEnabledSystems();
    assertTrue(enabledSystems.contains(TargetSystem.USM), "USM should be included when enabled");
  }

  /** Verifies that LLMOBS target system is not included when llmobs.enabled is false (default). */
  @Test
  void llmobsNotIncludedWhenDisabled() {
    Set<TargetSystem> enabledSystems = AgentInstaller.getEnabledSystems();
    assertFalse(
        enabledSystems.contains(TargetSystem.LLMOBS),
        "LLMOBS should not be included when disabled");
  }

  /** Verifies that LLMOBS target system is included when llmobs.enabled is true. */
  @Test
  @WithConfig(key = "llmobs.enabled", value = "true")
  void llmobsIncludedWhenEnabled() {
    Set<TargetSystem> enabledSystems = AgentInstaller.getEnabledSystems();
    assertTrue(
        enabledSystems.contains(TargetSystem.LLMOBS), "LLMOBS should be included when enabled");
  }
}
