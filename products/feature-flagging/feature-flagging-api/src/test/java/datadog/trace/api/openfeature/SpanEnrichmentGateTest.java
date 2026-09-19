package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpanEnrichmentGateTest {
  @Test
  void stableSettingOverridesLegacyAliasAndDefaultStaysOff() {
    final String stable = "dd.feature.flags.span.enrichment.enabled";
    final String legacy = "dd.experimental.flagging.provider.span.enrichment.enabled";
    final String previousStable = System.getProperty(stable);
    final String previousLegacy = System.getProperty(legacy);
    try {
      System.clearProperty(stable);
      System.setProperty(legacy, "true");
      assertTrue(SpanEnrichmentGate.isEnabled());
      System.setProperty(stable, "false");
      assertFalse(SpanEnrichmentGate.isEnabled());
      System.setProperty(stable, "true");
      System.setProperty(legacy, "false");
      assertTrue(SpanEnrichmentGate.isEnabled());
    } finally {
      restore(stable, previousStable);
      restore(legacy, previousLegacy);
    }
  }

  private static void restore(String name, String value) {
    if (value == null) System.clearProperty(name);
    else System.setProperty(name, value);
  }
}
