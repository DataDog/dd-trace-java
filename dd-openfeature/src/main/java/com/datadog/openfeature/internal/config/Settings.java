package com.datadog.openfeature.internal.config;

import com.datadog.openfeature.internal.connector.Connector;
import java.util.Locale;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the SDK settings. A setting is looked up from the {@link Connector} first, then from the
 * {@code dd.}-prefixed system property, then from the {@code DD_}-prefixed environment variable.
 */
public final class Settings {
  private static final Logger LOGGER = LoggerFactory.getLogger(Settings.class);

  public static final String CONFIGURATION_SOURCE_AGENTLESS = "agentless";
  public static final String CONFIGURATION_SOURCE_REMOTE_CONFIG = "remote_config";

  static final String FEATURE_FLAGS_ENABLED = "feature.flags.enabled";
  static final String FEATURE_FLAGS_CONFIGURATION_SOURCE = "feature.flags.configuration.source";
  static final String EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED =
      "experimental.flagging.provider.enabled";
  static final String AGENTLESS_BASE_URL = "feature.flags.configuration.source.agentless.base.url";
  static final String AGENTLESS_POLL_INTERVAL_SECONDS =
      "feature.flags.configuration.source.agentless.poll.interval.seconds";
  static final String AGENTLESS_REQUEST_TIMEOUT_SECONDS =
      "feature.flags.configuration.source.agentless.request.timeout.seconds";
  static final String SPAN_ENRICHMENT_ENABLED =
      "experimental.flagging.provider.span.enrichment.enabled";
  static final String EVALUATION_COUNTS_ENABLED = "flagging.evaluation.counts.enabled";
  static final String API_KEY = "api-key";
  static final String SITE = "site";
  static final String ENV = "env";
  static final String SERVICE = "service";
  static final String VERSION = "version";

  static final String DEFAULT_SITE = "datadoghq.com";
  static final int DEFAULT_POLL_INTERVAL_SECONDS = 30;
  static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;

  private final Connector connector;
  private final UnaryOperator<String> systemProperties;
  private final UnaryOperator<String> environment;

  Settings(
      final Connector connector,
      final UnaryOperator<String> systemProperties,
      final UnaryOperator<String> environment) {
    this.connector = connector;
    this.systemProperties = systemProperties;
    this.environment = environment;
  }

  /**
   * Creates the settings backed by the given connector, system properties and environment.
   *
   * @param connector the connector to look settings up from first.
   * @return the settings.
   */
  public static Settings load(final Connector connector) {
    return new Settings(connector, System::getProperty, System::getenv);
  }

  /**
   * @return whether Feature Flags is enabled.
   */
  public boolean isEnabled() {
    return resolveSource() != null;
  }

  /**
   * Resolves the configuration source, with the same semantics as the Datadog Java agent: an
   * explicit {@code DD_FEATURE_FLAGS_ENABLED=false} disables the product, an explicit source wins
   * over the legacy {@code DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED} toggle, and {@code agentless}
   * is the default.
   *
   * @return the configuration source, or {@code null} if Feature Flags is disabled or the source is
   *     not supported.
   */
  @Nullable
  public String configurationSource() {
    return resolveSource();
  }

  @Nullable
  private String resolveSource() {
    final Boolean enabled = getBoolean(FEATURE_FLAGS_ENABLED);
    final String source = normalize(get(FEATURE_FLAGS_CONFIGURATION_SOURCE));
    if (Boolean.FALSE.equals(enabled)) {
      return null;
    }
    if (source != null) {
      if (CONFIGURATION_SOURCE_AGENTLESS.equals(source)
          || CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(source)) {
        return source;
      }
      LOGGER.warn("Unsupported Feature Flags configuration source: {}", source);
      return null;
    }
    final Boolean legacyEnabled = getBoolean(EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED);
    if (legacyEnabled != null) {
      return legacyEnabled ? CONFIGURATION_SOURCE_REMOTE_CONFIG : null;
    }
    return CONFIGURATION_SOURCE_AGENTLESS;
  }

  /**
   * @return the custom CDN base URL, or {@code null} to use the Datadog managed one.
   */
  @Nullable
  public String agentlessBaseUrl() {
    final String url = get(AGENTLESS_BASE_URL);
    return url == null || url.trim().isEmpty() ? null : url.trim();
  }

  /**
   * @return the CDN poll interval, in seconds.
   */
  public int agentlessPollIntervalSeconds() {
    return getPositiveInt(AGENTLESS_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS);
  }

  /**
   * @return the CDN request timeout, in seconds.
   */
  public int agentlessRequestTimeoutSeconds() {
    return getPositiveInt(AGENTLESS_REQUEST_TIMEOUT_SECONDS, DEFAULT_REQUEST_TIMEOUT_SECONDS);
  }

  /**
   * @return whether span enrichment is enabled (off by default).
   */
  public boolean isSpanEnrichmentEnabled() {
    return Boolean.TRUE.equals(getBoolean(SPAN_ENRICHMENT_ENABLED));
  }

  /**
   * @return whether flag evaluation counts are sent to the event platform (on by default).
   */
  public boolean isEvaluationCountsEnabled() {
    return !Boolean.FALSE.equals(getBoolean(EVALUATION_COUNTS_ENABLED));
  }

  /**
   * @return the Datadog API key, or {@code null} if not set.
   */
  @Nullable
  public String apiKey() {
    return nonBlank(get(API_KEY));
  }

  /**
   * @return the Datadog site.
   */
  public String site() {
    final String site = nonBlank(get(SITE));
    return site == null ? DEFAULT_SITE : site;
  }

  /**
   * @return the environment, or {@code null} if not set.
   */
  @Nullable
  public String env() {
    return nonBlank(get(ENV));
  }

  /**
   * @return the service name, or {@code null} if not set.
   */
  @Nullable
  public String service() {
    return nonBlank(get(SERVICE));
  }

  /**
   * @return the application version, or {@code null} if not set.
   */
  @Nullable
  public String version() {
    return nonBlank(get(VERSION));
  }

  @Nullable
  String get(final String key) {
    String value = this.connector.setting(key);
    if (value == null) {
      value = this.systemProperties.apply("dd." + key);
    }
    if (value == null) {
      value = this.environment.apply(environmentVariableName(key));
    }
    return value;
  }

  @Nullable
  private Boolean getBoolean(final String key) {
    final String value = normalize(get(key));
    if (value == null) {
      return null;
    }
    // Same parsing as the agent: invalid values are ignored.
    if ("true".equals(value) || "1".equals(value)) {
      return Boolean.TRUE;
    }
    if ("false".equals(value) || "0".equals(value)) {
      return Boolean.FALSE;
    }
    LOGGER.warn("Invalid boolean value for {}: {}", key, value);
    return null;
  }

  private int getPositiveInt(final String key, final int defaultValue) {
    final String value = nonBlank(get(key));
    if (value == null) {
      return defaultValue;
    }
    try {
      final int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : defaultValue;
    } catch (final NumberFormatException e) {
      LOGGER.warn("Invalid value for {}: {}", key, value);
      return defaultValue;
    }
  }

  static String environmentVariableName(final String key) {
    return "DD_" + key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
  }

  @Nullable
  private static String normalize(@Nullable final String value) {
    final String trimmed = nonBlank(value);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  @Nullable
  private static String nonBlank(@Nullable final String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
