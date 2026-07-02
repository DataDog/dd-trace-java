package datadog.trace.api.config;

public class FeatureFlaggingConfig {

  public static final String FLAGGING_PROVIDER_ENABLED = "experimental.flagging.provider.enabled";

  /**
   * Killswitch for the EVP {@code flagevaluation} emission path. Default: enabled. Disabling it
   * turns off EVP flag-evaluation counts while leaving the OTel {@code feature_flag.evaluations}
   * metric path untouched. Maps to {@code DD_FLAGGING_EVALUATION_COUNTS_ENABLED}.
   */
  public static final String FLAGGING_EVALUATION_COUNTS_ENABLED =
      "flagging.evaluation.counts.enabled";
}
