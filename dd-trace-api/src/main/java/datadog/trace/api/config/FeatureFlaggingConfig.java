package datadog.trace.api.config;

/** Constant with names of configuration options for Feature Flags. */
public final class FeatureFlaggingConfig {

  public static final String FEATURE_FLAGS_ENABLED = "feature.flags.enabled";

  /** Use {@link #FEATURE_FLAGS_ENABLED} and {@link #FEATURE_FLAGS_CONFIGURATION_SOURCE}. */
  @Deprecated
  public static final String EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED =
      "experimental.flagging.provider.enabled";

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";

  public static final String FEATURE_FLAGS_EVALUATION_COUNTS_ENABLED =
      "feature.flags.evaluation.counts.enabled";

  public static final String FLAGGING_EVALUATION_COUNTS_ENABLED =
      "flagging.evaluation.counts.enabled";

  private FeatureFlaggingConfig() {}
}
