package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

import datadog.trace.api.ConfigOrigin;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    return System.getProperty(propertyNameToSystemPropertyName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.JVM_PROP;
  }
}
