package datadog.trace.core;

import static datadog.trace.test.util.SyntheticAccessors.assertNone;

import org.junit.jupiter.api.Test;

/** {@link CoreTracer} is {@code @NoSyntheticAccessors}: {@code CoreSpanBuilder} runs per span. */
class CoreTracerSyntheticAccessorsTest {
  @Test
  void nestHasNoSyntheticAccessors() {
    assertNone(CoreTracer.class);
  }
}
