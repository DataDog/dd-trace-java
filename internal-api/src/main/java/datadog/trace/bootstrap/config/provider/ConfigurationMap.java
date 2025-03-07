package datadog.trace.bootstrap.config.provider;

import java.util.HashMap;

public class ConfigurationMap extends HashMap<String, ConfigurationValue> {}

class ConfigurationValue {
  private final String value;

  public ConfigurationValue(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
