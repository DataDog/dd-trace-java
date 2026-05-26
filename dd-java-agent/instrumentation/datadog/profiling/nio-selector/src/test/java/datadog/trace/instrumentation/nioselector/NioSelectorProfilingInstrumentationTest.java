package datadog.trace.instrumentation.nioselector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores;
import org.junit.jupiter.api.Test;

class NioSelectorProfilingInstrumentationTest {

  @Test
  void selectorImplIsKnownMatchingType() {
    NioSelectorProfilingInstrumentation instrumentation = new NioSelectorProfilingInstrumentation();

    assertArrayEquals(
        new String[] {"sun.nio.ch.SelectorImpl"}, instrumentation.knownMatchingTypes());
  }

  @Test
  void selectorImplIsAllowedForBootstrapRetransformation() {
    assertFalse(GlobalIgnores.isIgnored("sun.nio.ch.SelectorImpl", false));
  }
}
