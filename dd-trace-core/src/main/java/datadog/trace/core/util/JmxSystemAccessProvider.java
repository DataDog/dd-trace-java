package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.List;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import lombok.extern.slf4j.Slf4j;

/** System provider based on JMX MXBeans */
@Slf4j
final class JmxSystemAccessProvider implements SystemAccessProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
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

  /**
   * @return the current process id, parsed from {@linkplain RuntimeMXBean#getName()}. Otherwise it
   *     returns 0
   */
  @Override
  public int getCurrentPid() {
    final String name = runtimeMXBean.getName();
    if (name == null) {
      return 0;
    }
    final int idx = name.indexOf('@');
    if (idx == -1) {
      return 0;
    }
    final String pid = name.substring(0, idx);
    return Integer.parseInt(pid);
  }

  /** invokes command on {@code com.sun.management:type=DiagnosticCommand} */
  @Override
  public String executeDiagnosticCommand(
      final String command, final Object[] args, final String[] sig) {
    ObjectName diagnosticCommandMBean = null;
    try {
      diagnosticCommandMBean = new ObjectName("com.sun.management:type=DiagnosticCommand");
    } catch (final MalformedObjectNameException ex) {
      log.debug("Error during executeDiagnosticCommand: ", ex);
      return ex.getMessage();
    }
    try {
      final Object result =
          ManagementFactory.getPlatformMBeanServer()
              .invoke(diagnosticCommandMBean, command, args, sig);
      return result != null ? result.toString().trim() : null;
    } catch (final Throwable ex) {
      log.debug("Error invoking diagnostic command: ", ex);
      return ex.getMessage();
    }
  }

  /** @return {@linkplain RuntimeMXBean#getInputArguments()} */
  @Override
  public List<String> getVMArguments() {
    List<String> args = Collections.emptyList();
    try {
      args = runtimeMXBean.getInputArguments();
    } catch (final Throwable ex) {
      log.debug("Error invoking runtimeMxBean.getInputArguments: ", ex);
    }
    return args;
  }
}
