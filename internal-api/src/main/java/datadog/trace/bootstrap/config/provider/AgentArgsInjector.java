package datadog.trace.bootstrap.config.provider;

import datadog.trace.util.Strings;
import java.util.Map;

public class AgentArgsInjector {

  /**
   * Parses agent arguments and sets corresponding system properties.
   *
   * @param agentArgs Agent arguments to be parsed and set
   */
  public static void injectAgentArgsConfig(String agentArgs) {
    Map<String, String> args = ConfigConverter.parseMap(agentArgs, "javaagent arguments", '=');
    injectAgentArgsConfig(args);
  }

  public static void injectAgentArgsConfig(Map<String, String> args) {
    if (args == null) {
      return;
    }
    for (Map.Entry<String, String> e : args.entrySet()) {
      String propertyName = e.getKey();
      String existingPropertyValue = System.getProperty(propertyName);
      if (existingPropertyValue != null) {
        // system properties should have higher priority than agent arguments
        continue;
      }

      String envVarName = Strings.toEnvVar(propertyName);
      String envVarValue = System.getenv(envVarName);
      if (envVarValue != null) {
        // env variables should have higher priority than agent arguments
        continue;
      }

      String propertyValue = e.getValue();
      System.setProperty(propertyName, propertyValue);
    }
  }
}
