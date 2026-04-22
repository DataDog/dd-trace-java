package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Date;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okio.Okio;

public class RemoteConfigServiceImpl
    implements RemoteConfigService, ConfigurationChangesTypedListener<ServerConfiguration> {

  private final ConfigurationPoller configurationPoller;

  public RemoteConfigServiceImpl(final SharedCommunicationObjects sco, final Config config) {
    configurationPoller = sco.configurationPoller(config);
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
    configurationPoller.removeListeners(Product.FFE_FLAGS);
    configurationPoller.stop();
  }

  @Override
  public void accept(
      final String configKey,
      @Nullable final ServerConfiguration configuration,
      final PollingRateHinter pollingRateHinter) {
    FeatureFlaggingGateway.dispatch(configuration);
  }

  static class UniversalFlagConfigDeserializer
      implements ConfigurationDeserializer<ServerConfiguration> {

    static final UniversalFlagConfigDeserializer INSTANCE = new UniversalFlagConfigDeserializer();

    private static final JsonAdapter<ServerConfiguration> V1_ADAPTER =
        new Moshi.Builder()
            .add(Date.class, new DateAdapter())
            .build()
            .adapter(ServerConfiguration.class);

    @Override
    public ServerConfiguration deserialize(final byte[] content) throws IOException {
      return V1_ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }
  }

  static class DateAdapter extends JsonAdapter<Date> {

    @Nullable
    @Override
    public Date fromJson(@Nonnull final JsonReader reader) throws IOException {
      final String date = reader.nextString();
      if (date == null) {
        return null;
      }
      try {
        // Use OffsetDateTime which handles variable precision fractional seconds (0-9 digits)
        // and UTC offsets (+01:00, -05:00, Z)
        final OffsetDateTime odt = OffsetDateTime.parse(date);
        return Date.from(odt.toInstant());
      } catch (Exception e) {
        // ignore wrongly set dates
        return null;
      }
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Date value)
        throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }
}
