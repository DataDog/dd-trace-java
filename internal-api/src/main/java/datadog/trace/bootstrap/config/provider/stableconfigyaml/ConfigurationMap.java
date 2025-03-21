package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.HashMap;
import java.util.Map;

// TODO: Update this comment from "stable configuration" to whatever product decides on for the name
// ConfigurationMap represents configuration key-values found in stable configuration files
public class ConfigurationMap extends HashMap<String, ConfigurationValue> {
  public ConfigurationMap() {
    return;
  }

  public ConfigurationMap(Map<String, ConfigurationValue> map) {
    super(map);
  }
}

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
