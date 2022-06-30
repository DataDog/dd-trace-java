package com.datadog.appsec.config;

import static com.datadog.appsec.config.AppSecConfig.MOSHI;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.remote_config.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import okio.Okio;

public class AppSecConfigDeserializer implements ConfigurationDeserializer<AppSecConfig> {
  public static final AppSecConfigDeserializer INSTANCE = new AppSecConfigDeserializer();

  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private AppSecConfigDeserializer() {}

  @Override
  public AppSecConfig deserialize(byte[] content) throws IOException {
    return deserialize(new ByteArrayInputStream(content));
  }

  public AppSecConfig deserialize(InputStream is) throws IOException {
    Map<String, Object> configMap = ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
    return AppSecConfig.valueOf(configMap);
  }
}
