package datadog.trace.config.inversion;

import java.util.Collections;
import java.util.List;

/**
 * This class uses {@link #GeneratedSupportedConfigurations} for handling supported configurations
 * for Config Inversion Can be extended for testing with custom configuration data.
 */
class SupportedConfigurationSource {

  /** @return Set of supported environment variable keys */
  public boolean supported(String env) {
    return GeneratedSupportedConfigurations.SUPPORTED.containsKey(env);
  }

  /** @return List of aliases for an environment variable */
  public List<String> getAliases(String env) {
    return GeneratedSupportedConfigurations.ALIASES.getOrDefault(env, Collections.emptyList());
  }

  /** @return Primary environment variable for a queried alias */
  public String primaryEnvFromAlias(String alias) {
    return GeneratedSupportedConfigurations.ALIAS_MAPPING.getOrDefault(alias, null);
  }

  /** @return Map of deprecated configurations */
  public String primaryEnvFromDeprecated(String deprecated) {
    return GeneratedSupportedConfigurations.DEPRECATED.getOrDefault(deprecated, null);
  }
}
