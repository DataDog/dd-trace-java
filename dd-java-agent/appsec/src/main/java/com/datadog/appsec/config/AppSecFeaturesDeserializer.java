package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Okio;

public class AppSecFeaturesDeserializer implements ConfigurationDeserializer<AppSecFeatures> {
  public static final AppSecFeaturesDeserializer INSTANCE = new AppSecFeaturesDeserializer();

  private static final JsonAdapter<AppSecFeatures> ADAPTER =
      new Moshi.Builder().build().adapter(AppSecFeatures.class);

  private AppSecFeaturesDeserializer() {}

  @Override
  public AppSecFeatures deserialize(byte[] content) throws IOException {
    return ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }
}
