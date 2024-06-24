package datadog.trace.bootstrap.instrumentation.jmx;

import java.util.HashMap;
import java.util.Map;
import javax.management.MBeanServer;

public class MBeanServerRegistry {
  private static final Map<String, MBeanServer> MAP = new HashMap<>();

  public static Map<String, MBeanServer> get() {
    return MAP;
  }
}
