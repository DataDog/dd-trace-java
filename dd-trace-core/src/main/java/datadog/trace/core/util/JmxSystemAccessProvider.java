package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import lombok.extern.slf4j.Slf4j;

/** System provider based on JMX MXBeans */
@Slf4j
final class JmxSystemAccessProvider implements SystemAccessProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private final boolean cpuTimeSupported = threadMXBean.isCurrentThreadCpuTimeSupported();

  public static final JmxSystemAccessProvider INSTANCE = new JmxSystemAccessProvider();

  /**
   * @return the actual thread CPU time as reported by {@linkplain
   *     ThreadMXBean#getCurrentThreadCpuTime()}
   */
  @Override
  public long getThreadCpuTime() {
    return cpuTimeSupported ? threadMXBean.getCurrentThreadCpuTime() : Long.MIN_VALUE;
  }
}
