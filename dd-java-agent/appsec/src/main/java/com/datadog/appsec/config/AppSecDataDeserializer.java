package com.datadog.appsec.config;

import static com.datadog.appsec.config.AppSecConfig.MOSHI;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import okio.Okio;

public class AppSecDataDeserializer
    implements ConfigurationDeserializer<List<Map<String, Object>>> {
  public static final AppSecDataDeserializer INSTANCE = new AppSecDataDeserializer();

  private static final JsonAdapter<Map<String, List<Map<String, Object>>>> ADAPTER =
      MOSHI.adapter(
          Types.newParameterizedType(
              Map.class,
              String.class,
              Types.newParameterizedType(
                  List.class, Types.newParameterizedType(Map.class, String.class, Object.class))));

  private AppSecDataDeserializer() {}

  @Override
  public List<Map<String, Object>> deserialize(byte[] content) throws IOException {
    return deserialize(new ByteArrayInputStream(content));
  }

  private List<Map<String, Object>> deserialize(InputStream is) throws IOException {
    Map<String, List<Map<String, Object>>> cfg = ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
    return cfg.get("rules_data");
  }
}
