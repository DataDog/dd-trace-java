package datadog.trace.api.featureflag.config;

/**
 * The Feature Flags settings the agent needs: the product switch and the configuration source. The
 * {@code dd-openfeature} SDK resolves them with the same semantics, see {@code
 * ConfigurationResolutionParityTest} in the {@code dd-openfeature-connector} instrumentation.
 */
public class FeatureFlaggingConfig {

  public static final String CONFIGURATION_SOURCE_AGENTLESS = "agentless";
  public static final String CONFIGURATION_SOURCE_REMOTE_CONFIG = "remote_config";

  public static final String FEATURE_FLAGS_ENABLED = "feature.flags.enabled";
  public static final String EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED =
      "experimental.flagging.provider.enabled";

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";

  private FeatureFlaggingConfig() {}
}
