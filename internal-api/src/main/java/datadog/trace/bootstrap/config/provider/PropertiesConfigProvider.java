package datadog.trace.bootstrap.config.provider;

import java.util.Properties;

public class PropertiesConfigProvider implements ConfigProvider.Source {
  private final Properties props;

  public PropertiesConfigProvider(Properties props) {
    assert props != null;
    this.props = props;
  }

  @Override
  public String get(String key) {
    return props.getProperty(key);
  }
}
