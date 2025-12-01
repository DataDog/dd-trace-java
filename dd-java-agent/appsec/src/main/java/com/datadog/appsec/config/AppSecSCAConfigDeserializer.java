package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Okio;

/**
 * Deserializer for Supply Chain Analysis (SCA) configuration from Remote Config. Converts JSON
 * payload from ASM_SCA product into typed AppSecSCAConfig objects.
 */
public class AppSecSCAConfigDeserializer implements ConfigurationDeserializer<AppSecSCAConfig> {

  public static final AppSecSCAConfigDeserializer INSTANCE = new AppSecSCAConfigDeserializer();

  private static final JsonAdapter<AppSecSCAConfig> ADAPTER =
      new Moshi.Builder().build().adapter(AppSecSCAConfig.class);

  private AppSecSCAConfigDeserializer() {}

  @Override
  public AppSecSCAConfig deserialize(byte[] content) throws IOException {
    if (content == null || content.length == 0) {
      return null;
    }
    return ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }
}
