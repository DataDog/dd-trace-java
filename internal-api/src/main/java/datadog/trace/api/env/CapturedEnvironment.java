package datadog.trace.api.env;

import datadog.trace.api.config.GeneralConfig;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
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

    @SuppressForbidden
    public ProcessInfo() {
      // Besides "sun.java.command" property is not an standard, all main JDKs has set this
      // property.
      // Tested on:
      // - OracleJDK, OpenJDK, AdoptOpenJDK, IBM JDK, Azul Zulu JDK, Amazon Coretto JDK
      final String command = System.getProperty("sun.java.command");
      if (command == null || command.isEmpty()) {
        return;
      }

      final String[] split = command.trim().split(" ");
      if (split.length == 0 || split[0].isEmpty()) {
        return;
      }

      final String candidate = split[0];
      if (candidate.toLowerCase(Locale.ROOT).endsWith(".jar")) {
        jarFile = new File(candidate);
      } else {
        mainClass = candidate;
      }
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

  CapturedEnvironment() {
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

    for (final Map.Entry<String, String> entry : props.entrySet()) {
      INSTANCE.properties.put(entry.getKey(), entry.getValue());
    }
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
    String inAas = System.getenv("DD_AZURE_APP_SERVICES");
    String siteName = System.getenv("WEBSITE_SITE_NAME");

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
