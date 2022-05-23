package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

final class EnvironmentConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    return System.getenv(propertyNameToEnvironmentVariableName(key));
  }
}
