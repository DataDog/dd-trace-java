package datadog.trace.util;

import javax.annotation.Nonnull;

public final class ConfigStrings {

  private ConfigStrings() {}

  public static String toEnvVar(String string) {
    return string.replace('.', '_').replace('-', '_').toUpperCase();
  }

  public static String toEnvVarLowerCase(String string) {
    return string.replace('.', '_').replace('-', '_').toLowerCase();
  }

  /**
   * Converts the property name, e.g. 'service.name' into a public environment variable name, e.g.
   * `DD_SERVICE_NAME`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing environment variable name
   */
  @Nonnull
  public static String propertyNameToEnvironmentVariableName(final String setting) {
    return "DD_" + toEnvVar(setting);
  }

  /**
   * Converts the system property name, e.g. 'dd.service.name' into a public environment variable
   * name, e.g. `DD_SERVICE_NAME`.
   *
   * @param setting The system property name, e.g. `dd.service.name`
   * @return The public facing environment variable name
   */
  @Nonnull
  public static String systemPropertyNameToEnvironmentVariableName(final String setting) {
    return setting.replace('.', '_').replace('-', '_').toUpperCase();
  }

  /**
   * Converts a config key in any form into its canonical environment variable name, regardless of
   * whether it arrived as a property name ({@code api-key}), a {@code dd.} system property ({@code
   * dd.api-key}), or a raw environment variable ({@code DD_API_KEY}) -- all canonicalize to {@code
   * DD_API_KEY}. Unlike {@link #propertyNameToEnvironmentVariableName(String)}, the {@code DD_}
   * namespace is added only when not already present, so a key already in env-var form is not
   * double-prefixed. {@code otel.}/{@code OTEL_} keys keep their own namespace.
   *
   * @param key The config key in any form
   * @return The canonical environment variable name
   */
  @Nonnull
  public static String toCanonicalEnvVar(final String key) {
    if (key.startsWith("otel.") || key.startsWith("OTEL_")) {
      return toEnvVar(key);
    }
    final String env = toEnvVar(key);
    return env.startsWith("DD_") ? env : "DD_" + env;
  }

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @Nonnull
  public static String propertyNameToSystemPropertyName(final String setting) {
    return "dd." + setting;
  }

  @Nonnull
  public static String normalizedHeaderTag(String str) {
    if (str.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder(str.length());
    int firstNonWhiteSpace = -1;
    int lastNonWhitespace = -1;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (Character.isWhitespace(c)) {
        builder.append('_');
      } else {
        firstNonWhiteSpace = firstNonWhiteSpace == -1 ? i : firstNonWhiteSpace;
        lastNonWhitespace = i;
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/') {
          builder.append(Character.toLowerCase(c));
        } else {
          builder.append('_');
        }
      }
    }
    if (firstNonWhiteSpace == -1) {
      return "";
    } else {
      str = builder.substring(firstNonWhiteSpace, lastNonWhitespace + 1);
      return str;
    }
  }

  @Nonnull
  public static String trim(final String string) {
    return null == string ? "" : string.trim();
  }
}
