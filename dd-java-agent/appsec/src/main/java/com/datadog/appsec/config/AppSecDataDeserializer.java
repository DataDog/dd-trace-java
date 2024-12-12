package com.datadog.appsec.config;

import static com.datadog.appsec.config.AppSecConfig.MOSHI;

import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.Okio;

public class AppSecDataDeserializer implements ConfigurationDeserializer<AppSecData> {
  public static final AppSecDataDeserializer INSTANCE = new AppSecDataDeserializer();

  private static final JsonAdapter<AppSecData> ADAPTER = MOSHI.adapter(AppSecData.class);

  private AppSecDataDeserializer() {}

  @Override
  public AppSecData deserialize(byte[] content) throws IOException {
    return deserialize(new ByteArrayInputStream(content));
  }

  private AppSecData deserialize(InputStream is) throws IOException {
    return ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
  }
}
