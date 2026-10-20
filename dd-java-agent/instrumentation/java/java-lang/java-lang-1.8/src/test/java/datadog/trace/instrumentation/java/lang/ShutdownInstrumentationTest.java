package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.TRACING;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ShutdownInstrumentationTest extends DDJavaSpecification {
  @Test
  void defaultConfigurationDoesNotEnableWholeAgentShutdownWithoutTracing() {
    assertFalse(new ShutdownInstrumentation().isApplicable(emptySet()));
  }

  @Test
  @WithConfig(key = "feature.flags.enabled", value = "true")
  void generatedTargetMaskDoesNotDependOnFeatureFlagBuildConfiguration() {
    final EnumSet<TargetSystem> systems = EnumSet.noneOf(TargetSystem.class);
    for (TargetSystem system : TargetSystem.values()) {
      if (new ShutdownInstrumentation().isApplicable(singleton(system))) {
        systems.add(system);
      }
    }
    assertEquals(EnumSet.of(TRACING), systems);
  }

  @Test
  @WithConfig(key = "feature.flags.enabled", value = "false")
  void doesNotInstrumentShutdownWhenTracingAndFeatureFlagsAreDisabled() {
    assertFalse(new ShutdownInstrumentation().isApplicable(emptySet()));
  }

  @Test
  @WithConfig(key = "feature.flags.enabled", value = "false")
  void preservesTracingShutdown() {
    assertTrue(new ShutdownInstrumentation().isApplicable(singleton(TRACING)));
  }

  @Test
  @WithConfig(key = "feature.flags.configuration.source", value = "agentless")
  void featureFlagsDoNotEnableWholeAgentShutdownWithoutTracing() {
    assertFalse(new ShutdownInstrumentation().isApplicable(emptySet()));
  }

  @Test
  @WithConfig(key = "feature.flags.enabled", value = "false")
  @WithConfig(key = "feature.flags.configuration.source", value = "agentless")
  void honorsExplicitDisableEvenWithAConfigurationSource() {
    assertFalse(new ShutdownInstrumentation().isApplicable(emptySet()));
  }
}
