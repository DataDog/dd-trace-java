package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.TRACING;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ShutdownInstrumentationTest {
  @Test
  void appliesOnlyToTracing() {
    final EnumSet<TargetSystem> systems = EnumSet.noneOf(TargetSystem.class);
    for (final TargetSystem system : TargetSystem.values()) {
      if (new ShutdownInstrumentation().isApplicable(singleton(system))) {
        systems.add(system);
      }
    }
    assertEquals(EnumSet.of(TRACING), systems);
  }
}
