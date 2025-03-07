package com.datadog.appsec.config;

import static com.datadog.appsec.config.AppSecConfig.MOSHI;

import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import okio.Okio;

public class AppSecDataDeserializer implements ConfigurationDeserializer<AppSecData> {
  public static final AppSecDataDeserializer INSTANCE = new AppSecDataDeserializer();

  private static final JsonAdapter<AppSecData> ADAPTER = MOSHI.adapter(AppSecData.class);

  private AppSecDataDeserializer() {}

  @Override
  @SuppressWarnings("unchecked")
  public AppSecData deserialize(byte[] content) throws IOException {
    if (content == null || content.length == 0) {
      return null;
    }
    InputStream is = new ByteArrayInputStream(content);
    AppSecData appSecData = ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
    if (appSecData == null) {
      return null;
    }
    is.reset();
    appSecData.setRawConfig(MOSHI.adapter(Map.class).fromJson(Okio.buffer(Okio.source(is))));
    if (appSecData.getRawConfig().containsKey("rules_data")) {
      appSecData.getRawConfig().put("rules", appSecData.getRawConfig().get("rules_data"));
      appSecData.getRawConfig().remove("rules_data");
    }
    if (appSecData.getRawConfig().containsKey("exclusion_data")) {
      appSecData.getRawConfig().put("exclusions", appSecData.getRawConfig().get("exclusion_data"));
      appSecData.getRawConfig().remove("exclusion_data");
    }

    return appSecData;
  }
}
