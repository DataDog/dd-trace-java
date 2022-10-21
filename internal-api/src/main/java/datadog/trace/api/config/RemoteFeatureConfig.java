package datadog.trace.api.config;

import static datadog.trace.api.config.RemoteConfigConfig.DEFAULT_REMOTE_CONFIG_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.DEFAULT_REMOTE_CONFIG_INITIAL_POLL_INTERVAL;
import static datadog.trace.api.config.RemoteConfigConfig.DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.RemoteConfigConfig.DEFAULT_REMOTE_CONFIG_TARGETS_KEY;
import static datadog.trace.api.config.RemoteConfigConfig.DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_INITIAL_POLL_INTERVAL;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY_ID;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_URL;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class RemoteFeatureConfig extends AbstractFeatureConfig {
  private final boolean remoteConfigEnabled;
  private final boolean remoteConfigIntegrityCheckEnabled;
  private final String remoteConfigUrl;
  private final int remoteConfigInitialPollInterval;
  private final long remoteConfigMaxPayloadSize;
  private final String remoteConfigTargetsKeyId;
  private final String remoteConfigTargetsKey;

  public RemoteFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    this.remoteConfigEnabled =
        configProvider.getBoolean(REMOTE_CONFIG_ENABLED, DEFAULT_REMOTE_CONFIG_ENABLED);
    this.remoteConfigIntegrityCheckEnabled =
        configProvider.getBoolean(
            REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED, DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED);
    this.remoteConfigUrl = configProvider.getString(REMOTE_CONFIG_URL);
    this.remoteConfigInitialPollInterval =
        configProvider.getInteger(
            REMOTE_CONFIG_INITIAL_POLL_INTERVAL, DEFAULT_REMOTE_CONFIG_INITIAL_POLL_INTERVAL);
    this.remoteConfigMaxPayloadSize =
        configProvider.getInteger(
                REMOTE_CONFIG_MAX_PAYLOAD_SIZE, DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE)
            * 1024L;
    this.remoteConfigTargetsKeyId =
        configProvider.getString(
            REMOTE_CONFIG_TARGETS_KEY_ID, DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID);
    this.remoteConfigTargetsKey =
        configProvider.getString(REMOTE_CONFIG_TARGETS_KEY, DEFAULT_REMOTE_CONFIG_TARGETS_KEY);
  }

  public long getRemoteConfigMaxPayloadSizeBytes() {
    return this.remoteConfigMaxPayloadSize;
  }

  public boolean isRemoteConfigEnabled() {
    return this.remoteConfigEnabled;
  }

  public boolean isRemoteConfigIntegrityCheckEnabled() {
    return this.remoteConfigIntegrityCheckEnabled;
  }

  public String getFinalRemoteConfigUrl() {
    return this.remoteConfigUrl;
  }

  public int getRemoteConfigInitialPollInterval() {
    return this.remoteConfigInitialPollInterval;
  }

  public String getRemoteConfigTargetsKeyId() {
    return this.remoteConfigTargetsKeyId;
  }

  public String getRemoteConfigTargetsKey() {
    return this.remoteConfigTargetsKey;
  }

  @Override
  public String toString() {
    return "RemoteFeatureConfig{"
        + "remoteConfigEnabled="
        + this.remoteConfigEnabled
        + ", remoteConfigIntegrityCheckEnabled="
        + this.remoteConfigIntegrityCheckEnabled
        + ", remoteConfigUrl='"
        + this.remoteConfigUrl
        + '\''
        + ", remoteConfigInitialPollInterval="
        + this.remoteConfigInitialPollInterval
        + ", remoteConfigMaxPayloadSize="
        + this.remoteConfigMaxPayloadSize
        + ", remoteConfigTargetsKeyId='"
        + this.remoteConfigTargetsKeyId
        + '\''
        + ", remoteConfigTargetsKey='"
        + this.remoteConfigTargetsKey
        + '\''
        + '}';
  }
}
