package datadog.trace.bootstrap.config.provider;

import java.util.Properties;

final class PropertiesConfigSource extends ConfigProvider.Source {
  private final Properties props;
  private final boolean useSystemPropertyFormat;

  public PropertiesConfigSource(Properties props, boolean useSystemPropertyFormat) {
    this.useSystemPropertyFormat = useSystemPropertyFormat;
    assert props != null;
    this.props = props;
  }

  @Override
  protected String get(String key) {
    return props.getProperty(
        useSystemPropertyFormat
            ? SystemPropertiesConfigSource.propertyNameToSystemPropertyName(key)
            : key);
  }
}
