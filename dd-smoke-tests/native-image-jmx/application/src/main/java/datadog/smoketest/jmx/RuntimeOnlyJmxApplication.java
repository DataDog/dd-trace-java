package datadog.smoketest.jmx;

import static java.lang.management.ManagementFactory.MEMORY_MXBEAN_NAME;
import static java.lang.management.ManagementFactory.getPlatformMBeanServer;
import static javax.management.MBeanServerFactory.findMBeanServer;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/** Exercises runtime JMX with GraalVM's default class initialization policy. */
public class RuntimeOnlyJmxApplication {
  public static void main(String[] args) throws Exception {
    System.out.println("Native application started");
    MBeanServer server = getPlatformMBeanServer();
    if (!findMBeanServer(null).contains(server)) {
      throw new AssertionError("The runtime MBean server was not registered");
    }
    if (!server.isRegistered(new ObjectName(MEMORY_MXBEAN_NAME))) {
      throw new AssertionError("The runtime memory MXBean was not registered");
    }
    System.out.println("Runtime JMX server available");
  }
}
