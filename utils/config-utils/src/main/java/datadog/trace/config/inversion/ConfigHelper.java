package datadog.trace.config.inversion;

import datadog.environment.EnvironmentVariables;
import datadog.trace.api.telemetry.ConfigInversionMetricCollectorProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigHelper {

  /** Config Inversion strictness policy for enforcement of undocumented environment variables */
  public enum StrictnessPolicy {
    STRICT,
    WARNING,
    TEST;

    private String displayName;

    StrictnessPolicy() {
      this.displayName = name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
      if (displayName == null) {
        displayName = name().toLowerCase(Locale.ROOT);
      }
      return displayName;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ConfigHelper.class);

  private static final ConfigHelper INSTANCE = new ConfigHelper();

  private StrictnessPolicy configInversionStrict = StrictnessPolicy.WARNING;

  // Cache for configs, init value is null
  private Map<String, String> configs;

  // Default to production source
  private SupportedConfigurationSource configSource = new SupportedConfigurationSource();

  public static ConfigHelper get() {
    return INSTANCE;
  }

  public void setConfigInversionStrict(StrictnessPolicy configInversionStrict) {
    this.configInversionStrict = configInversionStrict;
  }

  public StrictnessPolicy configInversionStrictFlag() {
    return configInversionStrict;
  }

  // Used only for testing purposes
  void setConfigurationSource(SupportedConfigurationSource testSource) {
    configSource = testSource;
  }

  /** Resetting config cache. Useful for cleaning up after tests. */
  void resetCache() {
    configs = null;
  }

  /** Reset all configuration data to the generated defaults. Useful for cleaning up after tests. */
  void resetToDefaults() {
    configSource = new SupportedConfigurationSource();
    this.configInversionStrict = StrictnessPolicy.WARNING;
  }

  public Map<String, String> getEnvironmentVariables() {
    if (configs != null) {
      return configs;
    }

    configs = new HashMap<>();
    Map<String, String> env = EnvironmentVariables.getAll();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      Map<String, String> aliasMapping = configSource.getAliasMapping();
      if (key.startsWith("DD_") || key.startsWith("OTEL_") || aliasMapping.containsKey(key)) {
        String baseConfig;
        if (configSource.getSupportedConfigurations().contains(key)) {
          configs.put(key, value);
          // If this environment variable is the alias of another, and we haven't processed the
          // original environment variable yet, handle it here.
        } else if (aliasMapping.containsKey(key)
            && !configs.containsKey(baseConfig = aliasMapping.get(key))) {
          List<String> aliasList = configSource.getAliases().get(baseConfig);
          for (String alias : aliasList) {
            if (env.containsKey(alias)) {
              configs.put(baseConfig, env.get(alias));
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
          log.warn(warning);
        }
      } else {
        configs.put(key, value);
      }
    }
    return configs;
  }

  public String getEnvironmentVariable(String name) {
    if (configs != null && configs.containsKey(name)) {
      return configs.get(name);
    }

    if ((name.startsWith("DD_") || name.startsWith("OTEL_"))
        && !configSource.getAliasMapping().containsKey(name)
        && !configSource.getSupportedConfigurations().contains(name)) {
      if (configInversionStrict != StrictnessPolicy.TEST) {
        ConfigInversionMetricCollectorProvider.get().setUndocumentedEnvVarMetric(name);
      }

      if (configInversionStrict == StrictnessPolicy.STRICT) {
        return null; // If strict mode is enabled, return null for unsupported configs
      }
    }

    String config = EnvironmentVariables.get(name);
    List<String> aliases;
    if (config == null && (aliases = configSource.getAliases().get(name)) != null) {
      for (String alias : aliases) {
        String aliasValue = EnvironmentVariables.get(alias);
        if (aliasValue != null) {
          return aliasValue;
        }
      }
    }
    return config;
  }
}
