package datadog.trace.bootstrap.instrumentation.jmx;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.management.MBeanServer;

public class MBeanServerRegistry {
  private static final ConcurrentMap<String, MBeanServer> MAP = new ConcurrentHashMap<>();

  public static MBeanServer getServer(final String mbeanName) {
    return MAP.get(mbeanName);
  }

  public static void putServer(final String mbeanName, final MBeanServer mBeanServer) {
    MAP.putIfAbsent(mbeanName, mBeanServer);
  }
}
