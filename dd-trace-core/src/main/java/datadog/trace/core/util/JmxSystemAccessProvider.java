package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import lombok.extern.slf4j.Slf4j;

/** System provider based on JMX MXBeans */
@Slf4j
final class JmxSystemAccessProvider implements SystemAccessProvider {
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

  /** */
  @Override
  public String executeDiagnosticCommand(String command, Object[] args, String[] sig) {
    ObjectName diagnosticCommandMBean = null;
    try {
      diagnosticCommandMBean = new ObjectName("com.sun.management:type=DiagnosticCommand");
    } catch (MalformedObjectNameException ex) {
      log.debug("Error during executeDiagnosticCommand: ", ex);
      return null;
    }
    try {
      Object result =
          ManagementFactory.getPlatformMBeanServer()
              .invoke(diagnosticCommandMBean, command, args, sig);
      return result != null ? result.toString() : null;
    } catch (Exception ex) {
      log.debug("Error invoking diagnostic command: ", ex);
      return null;
    }
  }
}
