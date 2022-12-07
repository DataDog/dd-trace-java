package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class PidHelperTest {
  @Test
  public void testPidCanBeSupplied() {
    PidHelper.supplyIfAbsent(() -> 12345L);
    assertEquals("12345", PidHelper.getPid(), "Expect PID to match supplied value");
    PidHelper.supplyIfAbsent(() -> 67890L);
    assertEquals("12345", PidHelper.getPid(), "Expect PID to not change once set");
  }
}
