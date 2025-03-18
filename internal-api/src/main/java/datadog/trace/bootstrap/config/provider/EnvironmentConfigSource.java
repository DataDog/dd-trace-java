package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;

final class EnvironmentConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    try {
      return System.getenv(propertyNameToEnvironmentVariableName(key));
    } catch (SecurityException e) {
      return null;
    }
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.ENV;
  }
}
