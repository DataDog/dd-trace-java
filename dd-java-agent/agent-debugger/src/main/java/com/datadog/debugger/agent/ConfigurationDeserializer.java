package com.datadog.debugger.agent;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Okio;

public class ConfigurationDeserializer
    implements datadog.remoteconfig.ConfigurationDeserializer<Configuration> {
  public static final ConfigurationDeserializer INSTANCE = new ConfigurationDeserializer();

  private static final JsonAdapter<Configuration> ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(Configuration.class);

  private ConfigurationDeserializer() {}

  @Override
  public Configuration deserialize(byte[] content) throws IOException {
    return ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }
}
