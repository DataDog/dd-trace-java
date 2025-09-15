package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigOrigin.JVM_PROP;
import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

import datadog.trace.api.ConfigOrigin;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {
  @Override
  protected String get(String key) {
    try {
      return System.getProperty(propertyNameToSystemPropertyName(key));
    } catch (SecurityException e) {
      return null;
    }
  }

  @Override
  public ConfigOrigin origin() {
    return JVM_PROP;
  }
}
