package datadog.environment;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressForbidden
public class ConfigHelper {
  private static boolean configInversionStrict;

  // Default to production source
  private static SupportedConfigurationSource configSource = new SupportedConfigurationSource();

  public static void setConfigInversionStrict(boolean configInversionStrict) {
    ConfigHelper.configInversionStrict = configInversionStrict;
  }

  public static boolean isConfigInversionStrict() {
    return configInversionStrict;
  }

  // Used only for testing purposes
  static void setConfigurationSource(SupportedConfigurationSource testSource) {
    configSource = testSource;
  }

  /** Reset all configuration data to the generated defaults. Useful for cleaning up after tests. */
  static void resetToDefaults() {
    configSource = new SupportedConfigurationSource();
    configInversionStrict = false;
  }

  public static Map<String, String> getEnvironmentVariables() {
    Map<String, String> env = System.getenv();
    Map<String, String> configs = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key.startsWith("DD_")
          || key.startsWith("OTEL_")
          || configSource.getAliasMapping().containsKey(key)) {
        if (configSource.getSupportedConfigurations().contains(key)) {
          configs.put(key, value);
          // If this environment variable is the alias of another, and we haven't processed the
          // original environment variable yet, handle it here.
        } else if (configSource.getAliasMapping().containsKey(key)
            && !configs.containsKey(configSource.getAliasMapping().get(key))) {
          List<String> aliasList =
              configSource.getAliases().get(configSource.getAliasMapping().get(key));
          for (String alias : aliasList) {
            if (env.containsKey(alias)) {
              configs.put(configSource.getAliasMapping().get(key), env.get(alias));
              break;
            }
          }
        }
        //        if (deprecatedConfigurations.containsKey(key)) {
        //          String warning = "Environment variable " + key + " is deprecated. " +
        //              (aliasMapping.containsKey(key)
        //                  ? "Please use " + aliasMapping.get(key) + " instead."
        //                  : deprecatedConfigurations.get(key));
        //          System.err.println(warning); // TODO: REPLACE with log call
        //        }
      } else {
        configs.put(key, value);
      }
    }
    return configs;
  }

  public static String getEnvironmentVariable(String name) {
    if ((name.startsWith("DD_") || name.startsWith("OTEL_"))
        && !configSource.getAliasMapping().containsKey(name)
        && !configSource.getSupportedConfigurations().contains(name)) {
      System.err.println(
          "Warning: Missing environment variable " + name + " from supported-configurations.json.");
      if (configInversionStrict) {
        return null; // If strict mode is enabled, return null for unsupported configs
      }
    }

    String config = EnvironmentVariables.get(name);
    if (config == null && configSource.getAliases().containsKey(name)) {
      for (String alias : configSource.getAliases().get(name)) {
        String aliasValue = EnvironmentVariables.get(alias);
        if (aliasValue != null) {
          return aliasValue;
        }
      }
    }
    return config;
  }
}
