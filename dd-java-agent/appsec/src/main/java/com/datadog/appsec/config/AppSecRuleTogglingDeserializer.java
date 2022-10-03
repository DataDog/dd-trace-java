package com.datadog.appsec.config;

import static com.datadog.appsec.config.AppSecConfig.MOSHI;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import okio.Okio;

public class AppSecRuleTogglingDeserializer
    implements ConfigurationDeserializer<Map<String, Boolean>> {
  public static final AppSecRuleTogglingDeserializer INSTANCE =
      new AppSecRuleTogglingDeserializer();

  private static final JsonAdapter<Map<String, List<Map<String, Object>>>> ADAPTER =
      MOSHI.adapter(
          Types.newParameterizedType(
              Map.class,
              String.class,
              Types.newParameterizedType(
                  List.class, Types.newParameterizedType(Map.class, String.class, Object.class))));

  private AppSecRuleTogglingDeserializer() {}

  @Override
  public Map<String, Boolean> deserialize(byte[] content) throws IOException {
    return deserialize(new ByteArrayInputStream(content));
  }

  private Map<String, Boolean> deserialize(InputStream is) throws IOException {
    Map<String, List<Map<String, Object>>> cfg = ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
    List<Map<String, Object>> rulesOverride = cfg.get("rules_override");
    if (rulesOverride == null) {
      return Collections.emptyMap();
    }
    return rulesOverride.stream()
        .collect(
            Collectors.toMap(
                m -> (String) m.get("id"),
                m -> (Boolean) m.getOrDefault("enabled", Boolean.FALSE)));
  }
}
