package datadog.trace.bootstrap.instrumentation.jmx;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.management.MBeanServer;

public class MBeanServerRegistry {
  private static final ConcurrentMap<String, MBeanServer> serverMap = new ConcurrentHashMap<>();

  public static MBeanServer getServer(final String serverClass) {
    return serverMap.get(serverClass);
  }

  public static void putServer(final String serverClass, final MBeanServer server) {
    serverMap.putIfAbsent(serverClass, server);
  }
}
