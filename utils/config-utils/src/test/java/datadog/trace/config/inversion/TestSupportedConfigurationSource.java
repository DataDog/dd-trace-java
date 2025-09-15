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
  public Set<String> getSupportedConfigurations() {
    return supported;
  }

  @Override
  public Map<String, List<String>> getAliases() {
    return aliases;
  }

  @Override
  public Map<String, String> getAliasMapping() {
    return aliasMapping;
  }

  @Override
  public Map<String, String> getDeprecatedConfigurations() {
    return deprecated;
  }
}
