package datadog.trace.bootstrap.config.provider;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.util.ShadowUtils;

public class ShadowedConfigSource extends ConfigProvider.Source {

  private final ConfigProvider.Source delegate;

  public ShadowedConfigSource(ConfigProvider.Source delegate) {
    this.delegate = delegate;
  }

  @Override
  protected String get(String key) {
    return delegate.get(ShadowUtils.getShadowedPropertyName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return delegate.origin();
  }
}
