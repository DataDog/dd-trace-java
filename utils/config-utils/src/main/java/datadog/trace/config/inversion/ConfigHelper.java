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
      String primaryEnv = configSource.primaryEnvFromAlias(key);
      if (key.startsWith("DD_") || key.startsWith("OTEL_") || null != primaryEnv) {
        if (configSource.supported(key)) {
          configs.put(key, value);
          // If this environment variable is the alias of another, and we haven't processed the
          // original environment variable yet, handle it here.
        } else if (null != primaryEnv && !configs.containsKey(primaryEnv)) {
          List<String> aliases = configSource.getAliases(primaryEnv);
          for (String alias : aliases) {
            if (env.containsKey(alias)) {
              configs.put(primaryEnv, env.get(alias));
              break;
            }
          }
        }
        String envFromDeprecated;
        if ((envFromDeprecated = configSource.primaryEnvFromDeprecated(key)) != null) {
          String warning =
              "Environment variable "
                  + key
                  + " is deprecated. Please use "
                  + (primaryEnv != null ? primaryEnv : envFromDeprecated)
                  + " instead.";
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
        && null != configSource.primaryEnvFromAlias(name)
        && !configSource.supported(name)) {
      if (configInversionStrict != StrictnessPolicy.TEST) {
        ConfigInversionMetricCollectorProvider.get().setUndocumentedEnvVarMetric(name);
      }

      if (configInversionStrict == StrictnessPolicy.STRICT) {
        return null; // If strict mode is enabled, return null for unsupported configs
      }
    }

    String config = EnvironmentVariables.get(name);
    List<String> aliases;
    if (config == null && (aliases = configSource.getAliases(name)) != null) {
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
