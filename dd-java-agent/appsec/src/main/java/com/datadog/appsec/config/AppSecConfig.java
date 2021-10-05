package com.datadog.appsec.config;

import com.datadog.appsec.util.Generated;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AppSecConfig {

  private static final JsonAdapter<AppSecConfig> ADAPTER =
      new Moshi.Builder().build().adapter(AppSecConfig.class);

  private String version;
  private List<Event> events;

  // We need to keep original raw config because DDWAF can't consume custom objects
  // Remove rawConfig when DDWAF will be able to get any object
  private Map<String, Object> rawConfig;

  private AppSecConfig() {}

  static AppSecConfig createFromMap(Map<String, Object> rawConfig) {
    AppSecConfig config = ADAPTER.fromJsonValue(rawConfig);
    if (config == null) {
      return null;
    }
    config.rawConfig = rawConfig;
    return config;
  }

  public List<Event> getEvents() {
    return events;
  }

  public Map<String, Object> getRawConfig() {
    return rawConfig;
  }

  public static class Event {
    private String id;
    private String name;
    private Map<String, String> tags;
    private Object conditions;
    private Object transformers;
    private Object action;

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

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AppSecConfig config = (AppSecConfig) o;
    return Objects.equals(version, config.version)
        && Objects.equals(events, config.events)
        && Objects.equals(rawConfig, config.rawConfig);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(version, events, rawConfig);
  }
}
