package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigOrigin.ENV;
import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.config.inversion.ConfigHelper;

final class EnvironmentConfigSource extends ConfigProvider.Source {
  @Override
  protected String get(String key) {
    return ConfigHelper.get().getEnvironmentVariable(propertyNameToEnvironmentVariableName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return ENV;
  }
}
