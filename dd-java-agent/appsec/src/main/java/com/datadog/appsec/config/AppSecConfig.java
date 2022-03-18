package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface AppSecConfig {

  Moshi MOSHI = new Moshi.Builder().build();
  JsonAdapter<AppSecConfigV1> ADAPTER_V1 = MOSHI.adapter(AppSecConfigV1.class);
  JsonAdapter<AppSecConfigV2> ADAPTER_V2 = MOSHI.adapter(AppSecConfigV2.class);

  String getVersion();

  List<Rule> getRules();

  Map<String, Object> getRawConfig();

  static AppSecConfig valueOf(Map<String, Object> rawConfig) throws IOException {
    if (rawConfig == null) {
      return null;
    }

    String version = String.valueOf(rawConfig.get("version"));
    if (version == null) {
      throw new IOException("Unable deserialize raw json config");
    }

    // For version 1.x
    if (version.startsWith("1.")) {
      AppSecConfigV1 config = ADAPTER_V1.fromJsonValue(rawConfig);
      config.rawConfig = rawConfig;
      return config;
    }

    // For version 2.x
    if (version.startsWith("2.")) {
      AppSecConfigV2 config = ADAPTER_V2.fromJsonValue(rawConfig);
      config.rawConfig = rawConfig;
      return config;
    }

    throw new IOException("Config version '" + version + "' is not supported");
  }

  class Rule {
    private String id;
    private String name;
    private Map<String, String> tags;
    private Object conditions;
    private Object transformers;

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public Map<String, String> getTags() {
      return tags;
    }
  }

  class AppSecConfigV1 implements AppSecConfig {

    private String version;
    private List<Rule> events;
    private Map<String, Object> rawConfig;

    @Override
    public String getVersion() {
      return null;
    }

    @Override
    public List<Rule> getRules() {
      return events;
    }

    @Override
    public Map<String, Object> getRawConfig() {
      return rawConfig;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AppSecConfigV1 that = (AppSecConfigV1) o;
      return Objects.equals(version, that.version)
          && Objects.equals(events, that.events)
          && Objects.equals(rawConfig, that.rawConfig);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + (version == null ? 0 : version.hashCode());
      hash = 31 * hash + (events == null ? 0 : events.hashCode());
      hash = 31 * hash + (rawConfig == null ? 0 : rawConfig.hashCode());
      return hash;
    }
  }

  class AppSecConfigV2 implements AppSecConfig {

    private String version;
    private List<Rule> rules;
    private Map<String, Object> rawConfig;

    @Override
    public String getVersion() {
      return null;
    }

    @Override
    public List<Rule> getRules() {
      return rules;
    }

    @Override
    public Map<String, Object> getRawConfig() {
      return rawConfig;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AppSecConfigV2 that = (AppSecConfigV2) o;
      return Objects.equals(version, that.version)
          && Objects.equals(rules, that.rules)
          && Objects.equals(rawConfig, that.rawConfig);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + (version == null ? 0 : version.hashCode());
      hash = 31 * hash + (rules == null ? 0 : rules.hashCode());
      hash = 31 * hash + (rawConfig == null ? 0 : rawConfig.hashCode());
      return hash;
    }
  }
}
