package datadog.trace.config.inversion;

import datadog.environment.EnvironmentVariables;
import datadog.trace.api.telemetry.ConfigInversionMetricCollectorProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigHelper {
  private static ConfigInversionStrictStyle configInversionStrict;

  // Default to production source
  private static SupportedConfigurationSource configSource = new SupportedConfigurationSource();

  public static void setConfigInversionStrict(ConfigInversionStrictStyle configInversionStrict) {
    ConfigHelper.configInversionStrict = configInversionStrict;
  }

  public static ConfigInversionStrictStyle configInversionStrictFlag() {
    return configInversionStrict;
  }

  // Used only for testing purposes
  static void setConfigurationSource(SupportedConfigurationSource testSource) {
    configSource = testSource;
  }

  /** Reset all configuration data to the generated defaults. Useful for cleaning up after tests. */
  static void resetToDefaults() {
    configSource = new SupportedConfigurationSource();
    configInversionStrict = ConfigInversionStrictStyle.WARNING;
  }

  public static Map<String, String> getEnvironmentVariables() {
    Map<String, String> env = EnvironmentVariables.getAll();
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
        // TODO: Follow-up - Add deprecation handling
        if (configSource.getDeprecatedConfigurations().containsKey(key)) {
          String warning =
              "Environment variable "
                  + key
                  + " is deprecated. "
                  + (configSource.getAliasMapping().containsKey(key)
                      ? "Please use " + configSource.getAliasMapping().get(key) + " instead."
                      : configSource.getDeprecatedConfigurations().get(key));
          System.err.println(warning);
        }
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
      if (configInversionStrict != ConfigInversionStrictStyle.TEST) {
        ConfigInversionMetricCollectorProvider.get().setUndocumentedEnvVarMetric(name);
      }

      if (configInversionStrict == ConfigInversionStrictStyle.STRICT) {
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
