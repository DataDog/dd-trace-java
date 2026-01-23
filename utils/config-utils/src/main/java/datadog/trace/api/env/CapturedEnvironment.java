package datadog.trace.api.env;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.config.inversion.ConfigHelper;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code CapturedEnvironment} instance keeps those {@code Config} values which are platform
 * dependant. Notice that this class must be considered internal. You should not depend on it
 * directly.
 */
public class CapturedEnvironment {

  public static class ProcessInfo {
    public String mainClass;
    public File jarFile;

    public ProcessInfo() {
      String jarName = JavaVirtualMachine.getJarFile();
      jarFile = jarName == null ? null : new File(jarName);
      mainClass = JavaVirtualMachine.getMainClass();
    }

    /**
     * Visible for testing
     *
     * @param mainClass
     * @param jarFile
     */
    ProcessInfo(String mainClass, File jarFile) {
      this.mainClass = mainClass;
      this.jarFile = jarFile;
    }
  }

  private static final CapturedEnvironment INSTANCE = new CapturedEnvironment();

  private final Map<String, String> properties;
  private ProcessInfo processInfo;

  private CapturedEnvironment() {
    properties = new HashMap<>();
    processInfo = new ProcessInfo();
    properties.put(GeneralConfig.SERVICE_NAME, autodetectServiceName());
  }

  public Map<String, String> getProperties() {
    return properties;
  }

  public ProcessInfo getProcessInfo() {
    return processInfo;
  }

  // Testing purposes
  static void useFixedEnv(final Map<String, String> props) {
    INSTANCE.properties.clear();
    INSTANCE.properties.putAll(props);
  }

  /**
   * For testing purposes.
   *
   * @param processInfo
   */
  static void useFixedProcessInfo(final ProcessInfo processInfo) {
    INSTANCE.processInfo = processInfo;
  }

  /**
   * Returns autodetected service name based on the java process command line. Typically, the
   * autodetection will return either the JAR filename or the java main class.
   */
  private String autodetectServiceName() {
    String inAas = ConfigHelper.env("DD_AZURE_APP_SERVICES");
    String siteName = ConfigHelper.env("WEBSITE_SITE_NAME");

    if (("true".equalsIgnoreCase(inAas) || "1".equals(inAas)) && siteName != null) {
      return siteName;
    }

    // preserve the original logic that is case sensitive on the .jar extension
    if (processInfo.jarFile != null && processInfo.jarFile.getName().endsWith(".jar")) {
      return processInfo.jarFile.getName().replace(".jar", "");
    } else {
      return processInfo.mainClass;
    }
  }

  public static CapturedEnvironment get() {
    return INSTANCE;
  }
}
