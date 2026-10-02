package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;

/**
 * Product API view of the span-enrichment gate resolved by the agent. OFF by default and distinct
 * from the provider-enabled gate. Shared so {@link Provider} (per construction) and {@link
 * DDEvaluator} (once at class load) read it the same way.
 */
final class SpanEnrichmentGate {

  private SpanEnrichmentGate() {}

  static boolean isEnabled() {
    try {
      return FeatureFlaggingGateway.isSpanEnrichmentEnabled();
    } catch (final Throwable t) {
      return false; // never let missing bootstrap support break construction
    }
  }
}
