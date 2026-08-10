package datadog.trace.bootstrap.debugger.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

public class TimeoutCheckerTest {
  @Test
  public void wallTimedOut() {
    long start = System.currentTimeMillis();
    Duration timeout = Duration.ofMillis(100);
    // assume that start & timestamp captured in instance below are very close
    WallTimeoutChecker timeoutChecker = new WallTimeoutChecker(timeout);
    assertFalse(timeoutChecker.isTimedOut(start + 1));
    assertTrue(timeoutChecker.isTimedOut(start + timeout.toMillis() * 2));
    timeoutChecker = new WallTimeoutChecker(start, timeout);
    assertFalse(timeoutChecker.isTimedOut(start + 1));
    assertTrue(timeoutChecker.isTimedOut(start + timeout.toMillis() * 2));
  }

  @Test
  public void cpuTimedOut() {
    CpuTimeoutChecker cpuTimeoutChecker = new CpuTimeoutChecker(Duration.ofMillis(1));
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); // not consume cpu
    assertFalse(cpuTimeoutChecker.isTimedOut());
    burnCpu(5_000_000);
    assertTrue(cpuTimeoutChecker.isTimedOut());
  }

  static volatile int counter;

  private static void burnCpu(int iterations) {
    for (int i = 0; i < iterations; i++) {
      counter++;
    }
  }

  @Test
  public void configTimeout() {
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationTimeoutCheckerMode()).thenReturn(TimeoutChecker.CPU);
    assertInstanceOf(CpuTimeoutChecker.class, TimeoutChecker.create(config, Duration.ofMillis(50)));
    when(config.getDynamicInstrumentationTimeoutCheckerMode()).thenReturn(TimeoutChecker.WALL);
    assertInstanceOf(
        WallTimeoutChecker.class, TimeoutChecker.create(config, Duration.ofMillis(50)));
  }
}
