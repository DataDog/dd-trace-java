package datadog.trace.api;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.EXPERIMENTAL_SPAN_ENRICHMENT_ENABLED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class ConfigFeatureFlaggingTest {

  @Test
  void spanEnrichmentDefaultsToDisabled() {
    assertFalse(Config.get().isFeatureFlaggingSpanEnrichmentEnabled());
  }

  @Test
  @WithConfig(key = EXPERIMENTAL_SPAN_ENRICHMENT_ENABLED, value = "true")
  void readsSpanEnrichmentConfiguration() {
    assertTrue(Config.get().isFeatureFlaggingSpanEnrichmentEnabled());
  }
}
