package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.util.SystemUtils;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    return SystemUtils.tryGetProperty(propertyNameToSystemPropertyName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.JVM_PROP;
  }
}
