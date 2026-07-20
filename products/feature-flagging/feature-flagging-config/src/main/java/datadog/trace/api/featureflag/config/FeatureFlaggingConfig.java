package datadog.trace.api.featureflag.config;

public class FeatureFlaggingConfig {

  public static final String CONFIGURATION_SOURCE_AGENTLESS = "agentless";
  public static final String CONFIGURATION_SOURCE_REMOTE_CONFIG = "remote_config";

  private static final Resolution DISABLED_RESOLUTION = new Resolution(false, null);
  private static final Resolution AGENTLESS_CONFIGURATION =
      new Resolution(true, CONFIGURATION_SOURCE_AGENTLESS);
  private static final Resolution REMOTE_CONFIG_CONFIGURATION =
      new Resolution(true, CONFIGURATION_SOURCE_REMOTE_CONFIG);

  public static final String FEATURE_FLAGS_ENABLED = "feature.flags.enabled";
  public static final String EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED =
      "experimental.flagging.provider.enabled";

  /**
   * Opt-in gate for APM span enrichment with feature-flag evaluation metadata. DISTINCT from {@link
   * #FEATURE_FLAGS_ENABLED} and OFF by default — enabling the provider does not enable span
   * enrichment.
   */
  public static final String EXPERIMENTAL_SPAN_ENRICHMENT_ENABLED =
      "experimental.flagging.provider.span.enrichment.enabled";

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL =
      "feature.flags.configuration.source.agentless.base.url";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_POLL_INTERVAL_SECONDS =
      "feature.flags.configuration.source.agentless.poll.interval.seconds";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_REQUEST_TIMEOUT_SECONDS =
      "feature.flags.configuration.source.agentless.request.timeout.seconds";

  public static Resolution resolveConfiguration(
      final Boolean providerEnabled,
      final String explicitSource,
      final Boolean legacyProviderEnabled) {
    if (Boolean.FALSE.equals(providerEnabled)) {
      return DISABLED_RESOLUTION;
    }
    if (explicitSource != null && !explicitSource.trim().isEmpty()) {
      final String source = explicitSource.trim();
      if (CONFIGURATION_SOURCE_AGENTLESS.equalsIgnoreCase(source)) {
        return AGENTLESS_CONFIGURATION;
      }
      if (CONFIGURATION_SOURCE_REMOTE_CONFIG.equalsIgnoreCase(source)) {
        return REMOTE_CONFIG_CONFIGURATION;
      }
      return DISABLED_RESOLUTION;
    }
    if (legacyProviderEnabled != null) {
      return legacyProviderEnabled ? REMOTE_CONFIG_CONFIGURATION : DISABLED_RESOLUTION;
    }
    return AGENTLESS_CONFIGURATION;
  }

  public static boolean isSupportedConfigurationSource(final String source) {
    if (source == null || source.trim().isEmpty()) {
      return true;
    }
    final String normalized = source.trim();
    return CONFIGURATION_SOURCE_AGENTLESS.equalsIgnoreCase(normalized)
        || CONFIGURATION_SOURCE_REMOTE_CONFIG.equalsIgnoreCase(normalized);
  }

  public static final class Resolution {
    private final boolean enabled;
    private final String source;

    private Resolution(final boolean enabled, final String source) {
      this.enabled = enabled;
      this.source = source;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public String getSource() {
      return source;
    }
  }

  private FeatureFlaggingConfig() {}
}
