package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigOrigin.ENV;
import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;

import datadog.environment.EnvironmentVariables;
import datadog.trace.api.ConfigOrigin;

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
