package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    return System.getProperty(propertyNameToSystemPropertyName(key));
  }
}
