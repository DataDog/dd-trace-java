package datadog.trace.bootstrap.config.provider;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.env.CapturedEnvironment;

public final class CapturedEnvironmentConfigSource extends ConfigProvider.Source {
  private final CapturedEnvironment env;

  public CapturedEnvironmentConfigSource() {
    this(CapturedEnvironment.get());
  }

  public CapturedEnvironmentConfigSource(CapturedEnvironment env) {
    this.env = env;
  }

  @Override
  protected String get(String key) throws ConfigSourceException {
    Object value = env.getProperties().get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String)) {
      throw new ConfigSourceException(value);
    }
    return (String) value;
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.ENV;
  }
}
