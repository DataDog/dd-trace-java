package datadog.config;

import static datadog.config.ConfigOrigin.ENV;
import static datadog.config.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.environment.EnvironmentVariables;

final class EnvironmentConfigSource extends ConfigProvider.Source {
  @Override
  protected String get(String key) {
    return EnvironmentVariables.get(propertyNameToEnvironmentVariableName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return ENV;
  }
}
