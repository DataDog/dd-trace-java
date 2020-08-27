package datadog.trace.bootstrap.config.provider;

public class EnvironmentConfigSource implements ConfigProvider.Source {
  @Override
  public String get(String key) {
    return System.getenv(key);
  }
}
