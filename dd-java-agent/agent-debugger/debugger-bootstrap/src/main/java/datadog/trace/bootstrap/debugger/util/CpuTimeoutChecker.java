package datadog.trace.bootstrap.debugger.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;

public class CpuTimeoutChecker implements TimeoutChecker {
  private final Duration timeOut;
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private final long startCpuTime;

  public CpuTimeoutChecker(Duration timeout) {
    this.timeOut = timeout;
    startCpuTime = threadMXBean.getCurrentThreadCpuTime();
  }

  @Override
  public boolean isTimedOut() {
    return threadMXBean.getCurrentThreadCpuTime() - startCpuTime >= timeOut.toNanos();
  }

  @Override
  public Duration getTimeOut() {
    return null;
  }
}
