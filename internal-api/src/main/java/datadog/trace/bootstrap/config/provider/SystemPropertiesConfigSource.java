package datadog.trace.bootstrap.config.provider;

import javax.annotation.Nonnull;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {
  private static final String PREFIX = "dd.";

  @Override
  protected String get(String key) {
    return System.getProperty(propertyNameToSystemPropertyName(key));
  }

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @Nonnull
  static String propertyNameToSystemPropertyName(final String setting) {
    return PREFIX + setting;
  }
}
