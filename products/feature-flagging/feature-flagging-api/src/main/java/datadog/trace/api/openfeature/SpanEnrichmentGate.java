package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

/**
 * Single source for reading the experimental span-enrichment gate, with full {@link ConfigProvider}
 * precedence (system property > stable config > env var {@code
 * DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED}). OFF by default; distinct from the
 * provider-enabled gate. Shared so {@link Provider} (per construction) and {@link DDEvaluator}
 * (once at class load) read it the same way.
 */
final class SpanEnrichmentGate {

  private SpanEnrichmentGate() {}

  static boolean isEnabled() {
    try {
      return ConfigProvider.getInstance()
          .getBoolean(FeatureFlaggingConfig.EXPERIMENTAL_SPAN_ENRICHMENT_ENABLED, false);
    } catch (final Throwable t) {
      return false; // never let config reading break construction
    }
  }
}
