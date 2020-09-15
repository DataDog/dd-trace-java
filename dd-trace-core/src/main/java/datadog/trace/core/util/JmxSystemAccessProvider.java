package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;

/** System provider based on JMX MXBeans */
public class JmxSystemAccessProvider implements SystemAccessProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

  public static final JmxSystemAccessProvider INSTANCE = new JmxSystemAccessProvider();

  /**
   * @return the actual thread CPU time as reported by {@linkplain
   *     ThreadMXBean#getCurrentThreadCpuTime()}
   */
  @Override
  public long getThreadCpuTime() {
    return threadMXBean.getCurrentThreadCpuTime();
  }

  /**
   * @return the current process id, parsed from {@linkplain RuntimeMXBean#getName()}. Otherwise it
   *     returns 0
   */
  @Override
  public int getCurrentPid() {
    String name = runtimeMXBean.getName();
    if (name == null) {
      return 0;
    }
    int idx = name.indexOf('@');
    if (idx == -1) {
      return 0;
    }
    String pid = name.substring(0, idx);
    return Integer.parseInt(pid);
  }
}
