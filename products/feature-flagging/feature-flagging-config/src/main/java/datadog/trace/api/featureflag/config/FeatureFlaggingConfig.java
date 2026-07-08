package datadog.trace.api.featureflag.config;

public class FeatureFlaggingConfig {

  public static final String FLAGGING_PROVIDER_ENABLED = "experimental.flagging.provider.enabled";

  /**
   * Opt-in gate for APM span enrichment with feature-flag evaluation metadata. The dot-form maps to
   * {@code DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED} via the dot-to-underscore +
   * {@code DD_} prefix normalization rule. This is DISTINCT from {@link #FLAGGING_PROVIDER_ENABLED}
   * and is OFF by default — enabling the provider does not enable span enrichment.
   */
  public static final String EXPERIMENTAL_SPAN_ENRICHMENT_ENABLED =
      "experimental.flagging.provider.span.enrichment.enabled";
}
