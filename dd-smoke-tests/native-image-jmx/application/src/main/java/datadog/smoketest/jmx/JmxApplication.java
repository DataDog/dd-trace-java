package datadog.smoketest.jmx;

import static java.lang.management.ManagementFactory.getPlatformMBeanServer;

import javax.management.ObjectName;
import javax.management.StandardMBean;

/** Reproduces the build-time MXBean registration performed by Oracle JDBC. */
public class JmxApplication {
  public static void main(String[] args) {
    if (!BuildTimeRegistration.INITIALIZED) {
      throw new AssertionError("Build-time registration did not run");
    }
    System.out.println("Native application started");
  }

  public static class BuildTimeRegistration {
    static final boolean INITIALIZED;

    static {
      try {
        getPlatformMBeanServer()
            .registerMBean(
                new StandardMBean(new Diagnostics(), DiagnosticsMXBean.class, true),
                new ObjectName("datadog.reproducer:type=Diagnostics"));
        System.out.println("Build-time MXBean registered");
        INITIALIZED = true;
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }

  public interface DiagnosticsMXBean {
    boolean isEnabled();
  }

  public static class Diagnostics implements DiagnosticsMXBean {
    @Override
    public boolean isEnabled() {
      return true;
    }
  }
}
