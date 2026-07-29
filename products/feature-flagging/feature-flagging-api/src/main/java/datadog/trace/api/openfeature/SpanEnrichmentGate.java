package datadog.trace.api.openfeature;

import de.thetaphi.forbiddenapis.SuppressForbidden;

/**
 * Single source for reading the experimental span-enrichment gate. A system property takes
 * precedence over the {@code DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED}. OFF by
 * default; distinct from the provider-enabled gate. Shared so {@link Provider} (per construction)
 * and {@link DDEvaluator} (once at class load) read it the same way.
 */
final class SpanEnrichmentGate {

  private SpanEnrichmentGate() {}

  @SuppressForbidden
  static boolean isEnabled() {
    try {
      final String property =
          System.getProperty("dd.experimental.flagging.provider.span.enrichment.enabled");
      final String value =
          property != null
              ? property
              : System.getenv("DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED");
      return Boolean.parseBoolean(value);
    } catch (final Throwable t) {
      return false; // never let config reading break construction
    }
  }
}
