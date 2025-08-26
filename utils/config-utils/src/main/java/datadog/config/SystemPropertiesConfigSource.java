package datadog.config;

import static datadog.config.ConfigOrigin.JVM_PROP;
import static datadog.config.util.Strings.propertyNameToSystemPropertyName;

import datadog.environment.SystemProperties;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {
  @Override
  protected String get(String key) {
    return SystemProperties.get(propertyNameToSystemPropertyName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return JVM_PROP;
  }
}
