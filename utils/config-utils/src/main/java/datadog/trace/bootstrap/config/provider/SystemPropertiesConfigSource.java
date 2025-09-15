package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigOrigin.JVM_PROP;
import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;

import datadog.environment.SystemProperties;
import datadog.trace.api.ConfigOrigin;

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
