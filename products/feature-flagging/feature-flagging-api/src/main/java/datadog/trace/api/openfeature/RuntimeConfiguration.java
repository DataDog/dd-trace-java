package datadog.trace.api.openfeature;

import datadog.openfeature.internal.http.CdnEndpointResolver;
import datadog.openfeature.internal.http.HttpConfigurationOptions;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Immutable provider runtime configuration resolved from explicit options or process settings. */
final class RuntimeConfiguration {

  enum Source {
    CDN,
    REMOTE_CONFIG,
    DISABLED
  }

  final Source source;
  final HttpConfigurationOptions http;

  private RuntimeConfiguration(final Source source, final HttpConfigurationOptions http) {
    this.source = source;
    this.http = http;
  }

  static RuntimeConfiguration resolve(final Provider.Options options) {
    final Boolean providerEnabled =
        options.providerEnabled != null
            ? options.providerEnabled
            : booleanSetting("dd.feature.flags.enabled", "DD_FEATURE_FLAGS_ENABLED");
    final String sourceValue =
        first(
            options.configurationSource,
            setting(
                "dd.feature.flags.configuration.source", "DD_FEATURE_FLAGS_CONFIGURATION_SOURCE"));
    final Boolean legacyProviderEnabled =
        options.legacyProviderEnabled != null
            ? options.legacyProviderEnabled
            : booleanSetting(
                "dd.experimental.flagging.provider.enabled",
                "DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED");
    final Source source = resolveSource(providerEnabled, sourceValue, legacyProviderEnabled);
    if (source != Source.CDN) {
      return new RuntimeConfiguration(source, null);
    }

    final String configuredBaseUrl =
        first(
            options.cdnBaseUrl,
            setting(
                "dd.feature.flags.configuration.source.agentless.base.url",
                "DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL"));
    final String site = first(options.site, setting("dd.site", "DD_SITE"), "datadoghq.com");
    final String env = first(options.environment, setting("dd.env", "DD_ENV"));
    final URI endpoint = CdnEndpointResolver.resolve(configuredBaseUrl, site, env);
    final Duration pollInterval =
        options.pollInterval != null
            ? options.pollInterval
            : seconds(
                setting(
                    "dd.feature.flags.configuration.source.agentless.poll.interval.seconds",
                    "DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_POLL_INTERVAL_SECONDS"),
                30);
    final Duration requestTimeout =
        options.requestTimeout != null
            ? options.requestTimeout
            : seconds(
                setting(
                    "dd.feature.flags.configuration.source.agentless.request.timeout.seconds",
                    "DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_REQUEST_TIMEOUT_SECONDS"),
                5);
    final String apiKey = first(options.apiKey, setting("dd.api.key", "DD_API_KEY"));
    return new RuntimeConfiguration(
        source,
        HttpConfigurationOptions.builder()
            .endpoint(endpoint)
            .pollInterval(pollInterval)
            .requestTimeout(requestTimeout)
            .apiKey(apiKey)
            .managedEndpoint(configuredBaseUrl == null)
            .build());
  }

  private static Source resolveSource(
      final Boolean providerEnabled,
      final String sourceValue,
      final Boolean legacyProviderEnabled) {
    if (Boolean.FALSE.equals(providerEnabled)) {
      return Source.DISABLED;
    }
    if (sourceValue == null || sourceValue.trim().isEmpty()) {
      if (legacyProviderEnabled != null) {
        return legacyProviderEnabled ? Source.REMOTE_CONFIG : Source.DISABLED;
      }
      return Source.CDN;
    }
    final String normalized = sourceValue.trim().toLowerCase(Locale.ROOT);
    if ("agentless".equals(normalized) || "cdn".equals(normalized)) {
      return Source.CDN;
    }
    if ("remote_config".equals(normalized)) {
      return Source.REMOTE_CONFIG;
    }
    throw new IllegalArgumentException(
        "Unsupported Feature Flagging configuration source: " + sourceValue);
  }

  private static Duration seconds(final String value, final long defaultValue) {
    if (value == null) {
      return Duration.ofSeconds(defaultValue);
    }
    try {
      final long seconds = Long.parseLong(value);
      return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(defaultValue);
    } catch (final NumberFormatException ignored) {
      return Duration.ofSeconds(defaultValue);
    }
  }

  @SuppressForbidden
  private static String setting(final String property, final String environment) {
    final String propertyValue = System.getProperty(property);
    return propertyValue != null ? propertyValue : System.getenv(environment);
  }

  private static Boolean booleanSetting(final String property, final String environment) {
    final String value = setting(property, environment);
    return value == null ? null : Boolean.valueOf(value);
  }

  private static String first(final String... values) {
    for (final String value : values) {
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return null;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RuntimeConfiguration)) {
      return false;
    }
    final RuntimeConfiguration that = (RuntimeConfiguration) other;
    return source == that.source
        && Objects.equals(
            http == null ? null : http.endpoint, that.http == null ? null : that.http.endpoint)
        && Objects.equals(
            http == null ? null : http.pollInterval,
            that.http == null ? null : that.http.pollInterval)
        && Objects.equals(
            http == null ? null : http.requestTimeout,
            that.http == null ? null : that.http.requestTimeout)
        && Objects.equals(
            http == null ? null : http.apiKey, that.http == null ? null : that.http.apiKey)
        && (http == null
            ? that.http == null
            : that.http != null && http.managedEndpoint == that.http.managedEndpoint);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        source,
        http == null ? null : http.endpoint,
        http == null ? null : http.pollInterval,
        http == null ? null : http.requestTimeout,
        http == null ? null : http.apiKey,
        http != null && http.managedEndpoint);
  }
}
