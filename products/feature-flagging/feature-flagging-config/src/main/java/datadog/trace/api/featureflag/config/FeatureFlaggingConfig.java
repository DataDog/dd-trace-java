package datadog.trace.api.featureflag.config;

public class FeatureFlaggingConfig {

  public static final String CONFIGURATION_SOURCE_AGENTLESS = "agentless";
  public static final String CONFIGURATION_SOURCE_OFFLINE = "offline";
  public static final String CONFIGURATION_SOURCE_REMOTE_CONFIG = "remote_config";

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

  public static String resolveConfigurationSource(
      final Boolean providerEnabled,
      final String explicitSource,
      final Boolean legacyProviderEnabled) {
    if (Boolean.FALSE.equals(providerEnabled)) {
      return CONFIGURATION_SOURCE_OFFLINE;
    }
    if (explicitSource != null && !explicitSource.trim().isEmpty()) {
      final String source = explicitSource.trim();
      if (CONFIGURATION_SOURCE_AGENTLESS.equalsIgnoreCase(source)) {
        return CONFIGURATION_SOURCE_AGENTLESS;
      }
      if (CONFIGURATION_SOURCE_REMOTE_CONFIG.equalsIgnoreCase(source)) {
        return CONFIGURATION_SOURCE_REMOTE_CONFIG;
      }
      return CONFIGURATION_SOURCE_OFFLINE;
    }
    if (legacyProviderEnabled != null) {
      return legacyProviderEnabled
          ? CONFIGURATION_SOURCE_REMOTE_CONFIG
          : CONFIGURATION_SOURCE_OFFLINE;
    }
    return CONFIGURATION_SOURCE_AGENTLESS;
  }

  public static boolean isSupportedConfigurationSource(final String source) {
    if (source == null || source.trim().isEmpty()) {
      return true;
    }
    final String normalized = source.trim();
    return CONFIGURATION_SOURCE_AGENTLESS.equalsIgnoreCase(normalized)
        || CONFIGURATION_SOURCE_REMOTE_CONFIG.equalsIgnoreCase(normalized)
        || CONFIGURATION_SOURCE_OFFLINE.equalsIgnoreCase(normalized);
  }

  private FeatureFlaggingConfig() {}
}
