package com.datadog.appsec.config;

import static com.datadog.appsec.config.AppSecConfig.MOSHI;

import com.squareup.moshi.JsonAdapter;
import datadog.remote_config.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Okio;

public class AppSecFeaturesDeserializer implements ConfigurationDeserializer<AppSecFeatures> {
  public static final AppSecFeaturesDeserializer INSTANCE = new AppSecFeaturesDeserializer();

  private static final JsonAdapter<AppSecFeatures> ADAPTER = MOSHI.adapter(AppSecFeatures.class);

  private AppSecFeaturesDeserializer() {}

  @Override
  public AppSecFeatures deserialize(byte[] content) throws IOException {
    return ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }
}
