package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** System provider based on JMX MXBeans */
final class JmxSystemAccessProvider implements SystemAccessProvider {
  private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
  private static final boolean CPU_TIME_SUPPORTED =
      THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported();

  public static final JmxSystemAccessProvider INSTANCE = new JmxSystemAccessProvider();

  /**
   * @return the actual thread CPU time as reported by {@linkplain
   *     ThreadMXBean#getCurrentThreadCpuTime()}
   */
  @Override
  public long getThreadCpuTime() {
    return CPU_TIME_SUPPORTED ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0;
  }
}
