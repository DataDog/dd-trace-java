package datadog.trace.api.featureflag.config;

/**
 * The Feature Flags settings the agent needs: the product switch and the configuration source. The
 * {@code dd-openfeature} SDK resolves them with the same semantics, see {@code
 * ConfigurationResolutionParityTest} in the {@code dd-openfeature-connector} instrumentation.
 */
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

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";

  public static Resolution resolveConfiguration(
      final Boolean providerEnabled,
      final String explicitSource,
      final Boolean legacyProviderEnabled) {
    final String normalizedSource = normalizeConfigurationSource(explicitSource);
    if (Boolean.FALSE.equals(providerEnabled)) {
      return new Resolution(false, normalizedSource);
    }
    if (normalizedSource != null) {
      if (CONFIGURATION_SOURCE_AGENTLESS.equals(normalizedSource)) {
        return AGENTLESS_CONFIGURATION;
      }
      if (CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(normalizedSource)) {
        return REMOTE_CONFIG_CONFIGURATION;
      }
      return new Resolution(false, normalizedSource);
    }
    if (legacyProviderEnabled != null) {
      return legacyProviderEnabled ? REMOTE_CONFIG_CONFIGURATION : DISABLED_RESOLUTION;
    }
    return AGENTLESS_CONFIGURATION;
  }

  public static boolean isSupportedConfigurationSource(final String source) {
    final String normalizedSource = normalizeConfigurationSource(source);
    return normalizedSource == null
        || CONFIGURATION_SOURCE_AGENTLESS.equals(normalizedSource)
        || CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(normalizedSource);
  }

  private static String normalizeConfigurationSource(final String source) {
    if (source == null) {
      return null;
    }
    final String normalized = source.trim().toLowerCase(java.util.Locale.ROOT);
    return normalized.isEmpty() ? null : normalized;
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
