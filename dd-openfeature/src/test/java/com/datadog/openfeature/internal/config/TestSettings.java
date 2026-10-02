package com.datadog.openfeature.internal.config;

import com.datadog.openfeature.internal.connector.Connector;
import java.util.HashMap;
import java.util.Map;

/** Builds {@link Settings} from explicit values, ignoring the JVM system properties and env. */
public final class TestSettings {
  private TestSettings() {}

  /**
   * @param keyValues alternating setting keys, without the {@code dd.} prefix, and values.
   * @return the settings backed by the given values only.
   */
  public static Settings of(final String... keyValues) {
    return of(Connector.NONE, keyValues);
  }

  /**
   * @param connector the connector to look settings up from first.
   * @param keyValues alternating setting keys, without the {@code dd.} prefix, and values.
   * @return the settings backed by the connector and the given values only.
   */
  public static Settings of(final Connector connector, final String... keyValues) {
    final Map<String, String> properties = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      properties.put("dd." + keyValues[i], keyValues[i + 1]);
    }
    return new Settings(connector, properties::get, name -> null);
  }
}
