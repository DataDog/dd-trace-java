package com.datadog.featureflag;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class OfflineConfigurationSource
    implements ConfigurationSourceService, FeatureFlaggingGateway.OfflineConfigListener {

  private final AtomicBoolean listening = new AtomicBoolean();
  private final AtomicBoolean configured = new AtomicBoolean();

  @Override
  public void init() {
    if (listening.compareAndSet(false, true)) {
      FeatureFlaggingGateway.addOfflineConfigListener(this);
    }
  }

  @Override
  public void close() {
    if (listening.compareAndSet(true, false)) {
      FeatureFlaggingGateway.removeOfflineConfigListener(this);
    }
  }

  @Override
  public void accept(final byte[] content) {
    final ServerConfiguration configuration;
    try {
      configuration =
          RemoteConfigServiceImpl.UniversalFlagConfigDeserializer.INSTANCE.deserialize(content);
    } catch (final IOException | RuntimeException error) {
      throw new IllegalArgumentException("Invalid offline Feature Flagging configuration", error);
    }
    if (configuration == null) {
      throw new IllegalArgumentException("Offline Feature Flagging configuration must not be null");
    }
    if (!configured.compareAndSet(false, true)) {
      throw new IllegalStateException("Offline Feature Flagging configuration is already set");
    }
    FeatureFlaggingGateway.dispatch(configuration);
  }
}
