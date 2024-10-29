package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.util.SystemUtils;

final class EnvironmentConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    return SystemUtils.tryGetEnv(propertyNameToEnvironmentVariableName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.ENV;
  }
}
