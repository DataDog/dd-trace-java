package datadog.trace.bootstrap.config.provider;

public class SystemPropertiesConfigSource implements ConfigProvider.Source {

  @Override
  public String get(String key) {
    return System.getProperty(key);
  }
}
