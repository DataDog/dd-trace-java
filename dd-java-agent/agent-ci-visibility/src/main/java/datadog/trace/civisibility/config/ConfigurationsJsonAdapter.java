package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;
import datadog.trace.api.civisibility.config.Configurations;
import java.util.HashMap;
import java.util.Map;

public final class ConfigurationsJsonAdapter {
  public static final ConfigurationsJsonAdapter INSTANCE = new ConfigurationsJsonAdapter();

  @FromJson
  public Configurations fromJson(Map<String, Object> json) {
    if (json == null) {
      return null;
    }
    return new Configurations(
        (String) json.get("os.platform"),
        (String) json.get("os.architecture"),
        (String) json.get("os.version"),
        (String) json.get("runtime.name"),
        (String) json.get("runtime.version"),
        (String) json.get("runtime.vendor"),
        (String) json.get("runtime.architecture"),
        (String) json.get("test.bundle"),
        (Map<String, String>) json.get("custom"));
  }

  @ToJson
  public Map<String, Object> toJson(Configurations configurations) {
    Map<String, Object> json = new HashMap<>();
    json.put("os.platform", configurations.getOsPlatform());
    // os.arch and os.architecture are duplicates: different endpoints expect different names
    json.put("os.arch", configurations.getOsArchitecture());
    json.put("os.architecture", configurations.getOsArchitecture());
    json.put("os.version", configurations.getOsVersion());
    json.put("runtime.name", configurations.getRuntimeName());
    json.put("runtime.version", configurations.getRuntimeVersion());
    json.put("runtime.vendor", configurations.getRuntimeVendor());
    json.put("runtime.architecture", configurations.getRuntimeArchitecture());
    json.put("test.bundle", configurations.getTestBundle());
    json.put("custom", configurations.getCustom());
    return json;
  }
}
