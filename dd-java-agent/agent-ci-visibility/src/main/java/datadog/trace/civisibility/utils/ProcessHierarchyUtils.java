package datadog.trace.civisibility.utils;

import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.util.Strings;
import java.net.InetSocketAddress;
import javax.annotation.Nullable;

public abstract class ProcessHierarchyUtils {

  private ProcessHierarchyUtils() {}

  /**
   * Session ID and module ID are supplied by the parent process if it runs with the tracer
   * attached. If session ID and module ID are not provided, either we are in the build system, or
   * we are in the tests JVM and the build system is not instrumented.
   */
  public static boolean isChild() {
    return System.getProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID))
        != null;
  }

  /**
   * Determines if current process runs in "headless mode", i.e. has no instrumented parent and is
   * not one of the supported build system processes.
   */
  public static boolean isHeadless() {
    return !isChild() && !isParent();
  }

  private static boolean isParent() {
    return isMavenParent() || isGradleDaemon();
  }

  private static boolean isMavenParent() {
    return System.getProperty("maven.home") != null
        && System.getProperty("classworlds.conf") != null;
  }

  private static boolean isGradleDaemon() {
    return ClassLoader.getSystemClassLoader().getResource("org/gradle/launcher/daemon/") != null;
  }

  public static long getParentSessionId() {
    // System.getProperty is used rather than Config,
    // because system variables can be set after config was initialized
    String systemProp =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
    if (systemProp == null) {
      throw new IllegalStateException("Parent session ID not available");
    }
    return Long.parseLong(systemProp);
  }

  public static long getParentModuleId() {
    // System.getProperty is used rather than Config,
    // because system variables can be set after config was initialized
    String systemProp =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
    if (systemProp == null) {
      throw new IllegalStateException("Parent module ID not available");
    }
    return Long.parseLong(systemProp);
  }

  @Nullable
  public static InetSocketAddress getSignalServerAddress() {
    // System.getProperty is used rather than Config,
    // because system variables can be set after config was initialized
    String host =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST));
    String port =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT));
    if (host != null && port != null) {
      return new InetSocketAddress(host, Integer.parseInt(port));
    } else {
      return null;
    }
  }
}
