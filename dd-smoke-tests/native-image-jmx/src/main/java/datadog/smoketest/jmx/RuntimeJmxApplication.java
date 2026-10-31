package datadog.smoketest.jmx;

import static java.lang.management.ManagementFactory.MEMORY_MXBEAN_NAME;
import static java.lang.management.ManagementFactory.getPlatformMBeanServer;
import static javax.management.MBeanServerFactory.findMBeanServer;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/** Checks that discarding builder JMX state preserves runtime JVM metrics. */
public class RuntimeJmxApplication {
  public static void main(String[] args) throws Exception {
    JmxApplication.main(args);

    MBeanServer server = getPlatformMBeanServer();
    if (server.isRegistered(new ObjectName("datadog.reproducer:type=Diagnostics"))) {
      throw new AssertionError("The build-time MXBean leaked into the executable");
    }
    if (!findMBeanServer(null).contains(server)) {
      throw new AssertionError("The runtime MBean server was not registered");
    }
    CompositeData heapUsage =
        (CompositeData) server.getAttribute(new ObjectName(MEMORY_MXBEAN_NAME), "HeapMemoryUsage");
    if ((Long) heapUsage.get("used") < 0) {
      throw new AssertionError("Cannot read runtime heap usage through JMX");
    }
    System.out.println("Runtime JMX metrics available");
  }
}
