package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class PatchLoggerTest {
  @Test
  void ignoresLazyMessages() {
    PatchLogger.getLogger("com.kenai.jffi.internal.StubLoader")
        .finest(
            () -> {
              fail("Disabled logging must not evaluate the message supplier");
              return "unused";
            });
  }
}
