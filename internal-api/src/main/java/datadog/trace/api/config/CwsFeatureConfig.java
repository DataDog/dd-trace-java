package datadog.trace.api.config;

import static datadog.trace.api.config.CwsConfig.CWS_ENABLED;
import static datadog.trace.api.config.CwsConfig.CWS_TLS_REFRESH;
import static datadog.trace.api.config.CwsConfig.DEFAULT_CWS_ENABLED;
import static datadog.trace.api.config.CwsConfig.DEFAULT_CWS_TLS_REFRESH;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class CwsFeatureConfig extends AbstractFeatureConfig {
  private final boolean cwsEnabled;
  private final int cwsTlsRefresh;

  public CwsFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    this.cwsEnabled = configProvider.getBoolean(CWS_ENABLED, DEFAULT_CWS_ENABLED);
    this.cwsTlsRefresh = configProvider.getInteger(CWS_TLS_REFRESH, DEFAULT_CWS_TLS_REFRESH);
  }

  public boolean isCwsEnabled() {
    return this.cwsEnabled;
  }

  public int getCwsTlsRefresh() {
    return this.cwsTlsRefresh;
  }

  @Override
  public String toString() {
    return "CwsFeatureConfig{"
        + "cwsEnabled="
        + this.cwsEnabled
        + ", cwsTlsRefresh="
        + this.cwsTlsRefresh
        + '}';
  }
}
