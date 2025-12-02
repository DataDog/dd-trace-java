package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import okio.BufferedSource;
import okio.Okio;

/**
 * Deserializer for Supply Chain Analysis (SCA) configuration from Remote Config. Converts JSON
 * payload from ASM_SCA product into typed AppSecSCAConfig objects.
 *
 * <p>Supports two formats:
 *
 * <ul>
 *   <li>Object with vulnerabilities property: {"vulnerabilities": [...]}
 *   <li>Direct array of vulnerabilities: [...]
 * </ul>
 */
public class AppSecSCAConfigDeserializer implements ConfigurationDeserializer<AppSecSCAConfig> {

  public static final AppSecSCAConfigDeserializer INSTANCE = new AppSecSCAConfigDeserializer();

  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<AppSecSCAConfig> CONFIG_ADAPTER =
      MOSHI.adapter(AppSecSCAConfig.class);

  private static final Type VULNERABILITY_LIST_TYPE =
      Types.newParameterizedType(List.class, AppSecSCAConfig.Vulnerability.class);
  private static final JsonAdapter<List<AppSecSCAConfig.Vulnerability>> VULNERABILITY_LIST_ADAPTER =
      MOSHI.adapter(VULNERABILITY_LIST_TYPE);

  private AppSecSCAConfigDeserializer() {}

  @Override
  public AppSecSCAConfig deserialize(byte[] content) throws IOException {
    if (content == null || content.length == 0) {
      return null;
    }

    // Read the content as string to detect format
    String jsonString = new String(content, "UTF-8").trim();

    if (jsonString.startsWith("[")) {
      // Direct array format: [{"advisory": "...", ...}]
      BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(content)));
      List<AppSecSCAConfig.Vulnerability> vulnerabilities =
          VULNERABILITY_LIST_ADAPTER.fromJson(source);
      AppSecSCAConfig config = new AppSecSCAConfig();
      config.vulnerabilities = vulnerabilities;
      return config;
    } else {
      // Object format: {"vulnerabilities": [...]}
      BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(content)));
      return CONFIG_ADAPTER.fromJson(source);
    }
  }
}
