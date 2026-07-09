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
}
