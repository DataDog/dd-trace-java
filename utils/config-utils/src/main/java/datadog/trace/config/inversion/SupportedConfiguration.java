package datadog.trace.config.inversion;

import java.util.List;

public class SupportedConfiguration {
  private final String version;
  private final String type;
  private final String defaultValue;
  private final List<String> aliases;
  private final List<String> propertyKeys;

  public SupportedConfiguration(
      String version,
      String type,
      String defaultValue,
      List<String> aliases,
      List<String> propertyKeys) {
    this.version = version;
    this.type = type;
    this.defaultValue = defaultValue;
    this.aliases = aliases;
    this.propertyKeys = propertyKeys;
  }

  public String getVersion() {
    return version;
  }

  public String getType() {
    return type;
  }

  public String getDefaultValue() {
    return defaultValue;
  }

  public List<String> getAliases() {
    return aliases;
  }

  public List<String> getPropertyKeys() {
    return propertyKeys;
  }
}
