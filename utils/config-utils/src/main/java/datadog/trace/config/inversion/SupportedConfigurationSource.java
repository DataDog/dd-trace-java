package datadog.trace.config.inversion;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class uses {@link #GeneratedSupportedConfigurations} for handling supported configurations
 * for Config Inversion Can be extended for testing with custom configuration data.
 */
class SupportedConfigurationSource {

  /** @return Set of supported configuration keys */
  public Set<String> getSupportedConfigurations() {
    return GeneratedSupportedConfigurations.SUPPORTED;
  }

  /** @return Map of configuration keys to their aliases */
  public Map<String, List<String>> getAliases() {
    return GeneratedSupportedConfigurations.ALIASES;
  }

  /** @return Map of alias keys to their primary configuration keys */
  public Map<String, String> getAliasMapping() {
    return GeneratedSupportedConfigurations.ALIAS_MAPPING;
  }

  /** @return Map of deprecated configurations */
  public Map<String, String> getDeprecatedConfigurations() {
    return GeneratedSupportedConfigurations.DEPRECATED;
  }
}
