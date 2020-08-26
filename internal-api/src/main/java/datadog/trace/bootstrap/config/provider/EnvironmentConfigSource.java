package datadog.trace.bootstrap.config.provider;

import java.util.regex.Pattern;
import lombok.NonNull;

final class EnvironmentConfigSource extends ConfigProvider.Source {
  private static final Pattern ENV_REPLACEMENT = Pattern.compile("[^a-zA-Z0-9_]");

  @Override
  protected String get(String key) {
    return System.getenv(propertyNameToEnvironmentVariableName(key));
  }

  /**
   * Converts the property name, e.g. 'service.name' into a public environment variable name, e.g.
   * `DD_SERVICE_NAME`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing environment variable name
   */
  @NonNull
  private static String propertyNameToEnvironmentVariableName(final String setting) {
    return ENV_REPLACEMENT
        .matcher(
            SystemPropertiesConfigSource.propertyNameToSystemPropertyName(setting).toUpperCase())
        .replaceAll("_");
  }
}
