package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.ConfigStrings.toEnvVar;

import datadog.environment.SystemProperties;
import datadog.trace.config.inversion.ConfigHelper;
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
      String existingPropertyValue = SystemProperties.get(propertyName);
      if (existingPropertyValue != null) {
        // system properties should have higher priority than agent arguments
        continue;
      }

      String envVarName = toEnvVar(propertyName);
      String envVarValue = ConfigHelper.get().getEnvironmentVariable(envVarName);
      if (envVarValue != null) {
        // env variables should have higher priority than agent arguments
        continue;
      }

      String propertyValue = e.getValue();
      System.setProperty(propertyName, propertyValue);
    }
  }
}
