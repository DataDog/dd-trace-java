package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    String propName = propertyNameToSystemPropertyName(key);
    String value = System.getProperty(propName);
    this.collect(propName, value);
    return value;
  }
}
