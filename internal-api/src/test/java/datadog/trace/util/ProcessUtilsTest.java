package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ProcessUtilsTest {

  @Test
  void currentJvmPathIsAvailableEverywhereWeTest() {
    assertFalse(ProcessUtils.getCurrentJvmPath().isEmpty());
  }
}
