package com.datadog.featureflag;

import com.datadog.featureflag.ufc.v1.ServerConfiguration;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import okio.Okio;

public class FeatureFlagRemoteConfigServiceImpl
    implements FeatureFlagRemoteConfigService,
        ConfigurationChangesTypedListener<ServerConfiguration> {

  private final ConfigurationPoller configurationPoller;
  private final List<Consumer<ServerConfiguration>> consumers = new CopyOnWriteArrayList<>();

  public FeatureFlagRemoteConfigServiceImpl(final ConfigurationPoller poller) {
    configurationPoller = poller;
  }

  @Override
  public void init() {
    configurationPoller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    configurationPoller.addListener(
        Product.FFE_FLAGS, UniversalFlagConfigDeserializer.INSTANCE, this);
    configurationPoller.start();
  }

  @Override
  public void close() {
    configurationPoller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    configurationPoller.stop();
  }

  @Override
  public void addConsumer(final Consumer<ServerConfiguration> consumer) {
    this.consumers.add(consumer);
  }

  @Override
  public void accept(
      final String configKey,
      @Nullable final ServerConfiguration configuration,
      final PollingRateHinter pollingRateHinter) {
    consumers.forEach(consumer -> consumer.accept(configuration));
  }

  private static class UniversalFlagConfigDeserializer
      implements ConfigurationDeserializer<ServerConfiguration> {

    public static final UniversalFlagConfigDeserializer INSTANCE =
        new UniversalFlagConfigDeserializer();

    private static final JsonAdapter<ServerConfiguration> V1_ADAPTER =
        new Moshi.Builder().build().adapter(ServerConfiguration.class);

    @Override
    public ServerConfiguration deserialize(final byte[] content) throws IOException {
      return V1_ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }
  }
}
