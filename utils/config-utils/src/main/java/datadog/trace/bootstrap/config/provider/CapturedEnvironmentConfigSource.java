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
  protected String get(String key) {
    return env.getProperties().get(key);
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.ENV;
  }
}
