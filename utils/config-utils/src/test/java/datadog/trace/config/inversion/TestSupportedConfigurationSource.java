package datadog.trace.config.inversion;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test implementation of SupportedConfigurationSource that uses custom configuration data */
class TestSupportedConfigurationSource extends SupportedConfigurationSource {
  private final Set<String> supported;
  private final Map<String, List<String>> aliases;
  private final Map<String, String> aliasMapping;
  private final Map<String, String> deprecated;

  public TestSupportedConfigurationSource(
      Set<String> supported,
      Map<String, List<String>> aliases,
      Map<String, String> aliasMapping,
      Map<String, String> deprecated) {
    this.supported = supported;
    this.aliases = aliases;
    this.aliasMapping = aliasMapping;
    this.deprecated = deprecated;
  }

  @Override
  public boolean supported(String env) {
    return supported.contains(env);
  }

  @Override
  public List<String> getAliases(String env) {
    return aliases.getOrDefault(env, null);
  }

  @Override
  public String primaryEnvFromAlias(String alias) {
    return aliasMapping.getOrDefault(alias, null);
  }

  @Override
  public String primaryEnvFromDeprecated(String deprecated) {
    return this.deprecated.getOrDefault(deprecated, null);
  }
}
