package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigOrigin.ENV;
import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.environment.ConfigHelper;
import datadog.trace.api.ConfigOrigin;

final class EnvironmentConfigSource extends ConfigProvider.Source {
  @Override
  protected String get(String key) {
    return ConfigHelper.getEnvironmentVariable(propertyNameToEnvironmentVariableName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return ENV;
  }
}
