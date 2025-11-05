package datadog.trace.api.openfeature.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.annotation.Nullable;
import okio.Okio;

public class RemoteConfigServiceImpl
    implements RemoteConfigService, ConfigurationChangesTypedListener<ServerConfiguration> {

  private final ConfigurationPoller configurationPoller;
  private final ServerConfigurationListener listener;

  public RemoteConfigServiceImpl(
      final SharedCommunicationObjects sco,
      final Config config,
      final ServerConfigurationListener listener) {
    configurationPoller = sco.configurationPoller(config);
    this.listener = listener;
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
  public void accept(
      final String configKey,
      @Nullable final ServerConfiguration configuration,
      final PollingRateHinter pollingRateHinter) {
    listener.onConfiguration(configuration);
  }

  static class UniversalFlagConfigDeserializer
      implements ConfigurationDeserializer<ServerConfiguration> {

    static final UniversalFlagConfigDeserializer INSTANCE = new UniversalFlagConfigDeserializer();

    private static final JsonAdapter<ServerConfiguration> V1_ADAPTER =
        new Moshi.Builder().build().adapter(ServerConfiguration.class);

    @Override
    public ServerConfiguration deserialize(final byte[] content) throws IOException {
      return V1_ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }
  }
}
