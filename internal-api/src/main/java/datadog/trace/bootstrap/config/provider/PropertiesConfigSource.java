package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

import java.util.Properties;

final class PropertiesConfigSource extends ConfigProvider.Source {
  // start key with underscore, so it isn't visible using the public 'get' method
  static final String CONFIG_FILE_STATUS = "_dd.config.file.status";

  private final Properties props;
  private final boolean useSystemPropertyFormat;

  public PropertiesConfigSource(Properties props, boolean useSystemPropertyFormat) {
    assert props != null;
    this.props = props;
    this.useSystemPropertyFormat = useSystemPropertyFormat;
  }

  public String getConfigFileStatus() {
    return props.getProperty(CONFIG_FILE_STATUS);
  }

  @Override
  protected String get(String key) {
    String propName = useSystemPropertyFormat ? propertyNameToSystemPropertyName(key) : key;
    String value = props.getProperty(propName);
    collect(propName, value);
    return value;
  }
}
