package com.datadog.debugger.agent;

import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import java.io.IOException;

public class ConfigurationChangesListener implements ProductListener {
  private final ConfigurationUpdater updater;
  private final ConfigurationDeserializer adapter;
  private Configuration lastConfiguration = null;

  ConfigurationChangesListener(ConfigurationUpdater updater) {
    this.updater = updater;
    this.adapter = ConfigurationDeserializer.INSTANCE;
  }

  @Override
  public void accept(
      ParsedConfigKey configKey,
      byte[] content,
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    // TODO: capture configurations based on configKey.getConfigID.
    Configuration config = adapter.deserialize(content);
    lastConfiguration = config;
  }

  @Override
  public void remove(
      ParsedConfigKey configKey,
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    lastConfiguration = null;
  }

  @Override
  public void commit(
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {
    // TODO: run merge logic before calling updater.accept
    updater.accept(lastConfiguration);
  }
}
