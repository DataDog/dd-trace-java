package datadog.trace.api.featureflag.config;

public class FeatureFlaggingConfig {

  public static final String FLAGGING_PROVIDER_ENABLED = "experimental.flagging.provider.enabled";

  /**
   * Opt-in gate for APM span enrichment with feature-flag evaluation metadata. DISTINCT from {@link
   * #FLAGGING_PROVIDER_ENABLED} and OFF by default — enabling the provider does not enable span
   * enrichment.
   */
  public static final String EXPERIMENTAL_SPAN_ENRICHMENT_ENABLED =
      "experimental.flagging.provider.span.enrichment.enabled";

  /**
   * Killswitch for the EVP {@code flagevaluation} emission path. Default: enabled. Disabling it
   * turns off EVP flag-evaluation counts while leaving the OTel {@code feature_flag.evaluations}
   * metric path untouched. Maps to {@code DD_FLAGGING_EVALUATION_COUNTS_ENABLED}.
   */
  public static final String FLAGGING_EVALUATION_COUNTS_ENABLED =
      "flagging.evaluation.counts.enabled";

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL =
      "feature.flags.configuration.source.agentless.base.url";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_POLL_INTERVAL_SECONDS =
      "feature.flags.configuration.source.agentless.poll.interval.seconds";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_REQUEST_TIMEOUT_SECONDS =
      "feature.flags.configuration.source.agentless.request.timeout.seconds";

  private FeatureFlaggingConfig() {}
}
