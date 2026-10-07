package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.TRACING;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class ShutdownInstrumentationTest extends DDJavaSpecification {
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
  void enablesFeatureFlagShutdownWithoutTracing() {
    assertTrue(new ShutdownInstrumentation().isApplicable(emptySet()));
  }

  @Test
  @WithConfig(key = "feature.flags.enabled", value = "false")
  @WithConfig(key = "feature.flags.configuration.source", value = "agentless")
  void honorsExplicitDisableEvenWithAConfigurationSource() {
    assertFalse(new ShutdownInstrumentation().isApplicable(emptySet()));
  }
}
