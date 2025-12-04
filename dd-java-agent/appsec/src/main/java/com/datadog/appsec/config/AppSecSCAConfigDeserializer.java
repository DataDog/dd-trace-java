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
 * Deserializer for SCA configuration from Remote Config.
 *
 * <p>Converts JSON payload from Remote Config into typed AppSecSCAConfig objects. The backend
 * sends vulnerabilities as a direct JSON array: [{"advisory": "...", "cve": "...", ...}]
 */
public class AppSecSCAConfigDeserializer implements ConfigurationDeserializer<AppSecSCAConfig> {

  public static final AppSecSCAConfigDeserializer INSTANCE = new AppSecSCAConfigDeserializer();

  private static final Type VULNERABILITY_LIST_TYPE =
      Types.newParameterizedType(List.class, AppSecSCAConfig.Vulnerability.class);
  private static final JsonAdapter<List<AppSecSCAConfig.Vulnerability>> VULNERABILITY_LIST_ADAPTER =
      new Moshi.Builder().build().adapter(VULNERABILITY_LIST_TYPE);

  private AppSecSCAConfigDeserializer() {}

  @Override
  public AppSecSCAConfig deserialize(byte[] content) throws IOException {
    if (content == null || content.length == 0) {
      return null;
    }

    // Backend sends vulnerabilities as a JSON array: [...]
    BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(content)));
    List<AppSecSCAConfig.Vulnerability> vulnerabilities =
        VULNERABILITY_LIST_ADAPTER.fromJson(source);

    // Wrap the list in an AppSecSCAConfig object
    AppSecSCAConfig config = new AppSecSCAConfig();
    config.vulnerabilities = vulnerabilities;
    return config;
  }
}
