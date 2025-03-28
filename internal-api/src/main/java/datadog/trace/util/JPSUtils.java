package datadog.trace.util;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JPSUtils {
  private static final Logger log = LoggerFactory.getLogger(JPSUtils.class);

  public static Set<String> getVMPids() {
    Set<String> vmPids = new HashSet<>();
    try {
      Class<?> monitoredHostClass = Class.forName("sun.jvmstat.monitor.MonitoredHost");
      Method getMonitoredHostMethod =
          monitoredHostClass.getDeclaredMethod("getMonitoredHost", String.class);
      Object vmHost = getMonitoredHostMethod.invoke(null, "localhost");
      for (Integer vmPid :
          (List<Integer>) monitoredHostClass.getDeclaredMethod("activeVms").invoke(vmHost)) {
        vmPids.add(vmPid.toString());
      }
    } catch (Exception e) {
      log.warn("Failed to invoke jvmstat with exception ", e);
      return null;
    }
    return vmPids;
  }
}
