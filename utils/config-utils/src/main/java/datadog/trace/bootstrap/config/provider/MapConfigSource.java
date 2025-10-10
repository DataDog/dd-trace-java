package datadog.trace.bootstrap.config.provider;

import datadog.trace.api.ConfigOrigin;
import java.util.Map;
import java.util.function.Function;

final class MapConfigSource extends ConfigProvider.Source {

  private final Map<String, String> properties;
  private final Function<String, String> keyTransformer;
  private final ConfigOrigin origin;

  MapConfigSource(
      Map<String, String> properties,
      Function<String, String> keyTransformer,
      ConfigOrigin origin) {
    this.properties = properties;
    this.keyTransformer = keyTransformer;
    this.origin = origin;
  }

  @Override
  protected String get(String key) {
    return properties.get(keyTransformer.apply(key));
  }

  @Override
  public ConfigOrigin origin() {
    return origin;
  }
}
