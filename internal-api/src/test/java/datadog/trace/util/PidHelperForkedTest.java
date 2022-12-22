package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class PidHelperForkedTest {
  @Test
  public void testPidCanBeSupplied() {
    PidHelper.Fallback.set(() -> "12345");
    assertEquals("12345", PidHelper.getPid(), "Expect PID to match supplied value");
    PidHelper.Fallback.set(() -> "67890");
    assertEquals("12345", PidHelper.getPid(), "Expect PID to not change once set");
  }
}
