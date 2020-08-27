package datadog.trace.bootstrap.config.provider;

import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigProvider {
  protected final ConfigProvider.Source[] sources;

  private ConfigProvider(ConfigProvider.Source... sources) {
    this.sources = sources;
  }

  public final String getString(String key) {
    return getString(key, null);
  }

  public final String getString(String key, String defaultValue) {
    for (ConfigProvider.Source source : sources) {
      String value = source.get(key);
      if (value != null) {
        return value;
      }
    }
    return defaultValue;
  }

  public final Boolean getBoolean(String key) {
    return get(key, null, Boolean.class);
  }

  public final boolean getBoolean(String key, boolean defaultValue) {
    return get(key, defaultValue, boolean.class);
  }

  public final Integer getInteger(String key) {
    return get(key, null, Integer.class);
  }

  public final int getInteger(String key, int defaultValue) {
    return get(key, defaultValue, int.class);
  }

  public final Double getDouble(String key) {
    return get(key, null, Double.class);
  }

  public final double getDouble(String key, double defaultValue) {
    return get(key, defaultValue, double.class);
  }

  private <T> T get(String key, T defaultValue, Class<T> type) {
    for (ConfigProvider.Source source : sources) {
      T value = ConfigConverter.valueOf(source.get(key), type);
      if (value != null) {
        return value;
      }
    }
    return defaultValue;
  }

  public final Map<String, String> getMergedMap(String key) {
    Map<String, String> merged = new HashMap<>();
    // reverse iterate to allow overrides
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key);
      merged.putAll(Config.parseMap(value, key));
    }
    return merged;
  }

  public static ConfigProvider createDefault() {
    Properties configProperties = Config.loadConfigurationFile();
    if (configProperties.isEmpty()) {
      return new ConfigProvider(new SystemPropertiesConfigSource(), new EnvironmentConfigSource());
    } else {
      return new ConfigProvider(
          new SystemPropertiesConfigSource(),
          new EnvironmentConfigSource(),
          new PropertiesConfigSource(configProperties));
    }
  }

  public interface Source {
    String get(String key);
  }
}
