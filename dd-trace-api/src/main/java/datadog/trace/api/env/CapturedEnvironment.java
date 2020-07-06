package datadog.trace.api.env;

import datadog.trace.api.config.GeneralConfig;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@code CapturedEnvironment} instance keeps those {@code Config} values which are platform
 * dependant.
 */
@Slf4j
public class CapturedEnvironment {

  private static final CapturedEnvironment INSTANCE = new CapturedEnvironment();

  @Getter private final Map<String, String> properties;

  CapturedEnvironment() {
    properties = new HashMap<>();
    properties.put(GeneralConfig.SERVICE_NAME, autodetectServiceName());
  }

  // Testing purposes
  static void useFixedEnv(final Map<String, String> props) {
    INSTANCE.properties.clear();

    for (final Map.Entry<String, String> entry : props.entrySet()) {
      INSTANCE.properties.put(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Returns autodetected service name based on the java process command line. Typically, the
   * autodetection will return either the JAR filename or the java main class.
   */
  private String autodetectServiceName() {
    // Besides "sun.java.command" property is not an standard, all main JDKs has set this property.
    // Tested on:
    // - OracleJDK, OpenJDK, AdoptOpenJDK, IBM JDK, Azul Zulu JDK, Amazon Coretto JDK
    return extractJarOrClass(System.getProperty("sun.java.command"));
  }

  private String extractJarOrClass(final String command) {
    if (command == null || command.equals("")) {
      return null;
    }

    final String[] split = command.trim().split(" ");
    if (split.length == 0 || split[0].equals("")) {
      return null;
    }

    final String candidate = split[0];
    if (candidate.endsWith(".jar")) {
      return new File(candidate).getName();
    }

    return candidate;
  }

  public static CapturedEnvironment get() {
    return INSTANCE;
  }
}
