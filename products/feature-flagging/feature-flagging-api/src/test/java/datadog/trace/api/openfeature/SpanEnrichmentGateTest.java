package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpanEnrichmentGateTest {
  @Test
  void experimentalSettingControlsEnrichmentAndDefaultStaysOff() {
    final String setting = "dd.experimental.flagging.provider.span.enrichment.enabled";
    final String previous = System.getProperty(setting);
    try {
      System.clearProperty(setting);
      assertFalse(SpanEnrichmentGate.isEnabled());
      System.setProperty(setting, "true");
      assertTrue(SpanEnrichmentGate.isEnabled());
      System.setProperty(setting, "false");
      assertFalse(SpanEnrichmentGate.isEnabled());
    } finally {
      restore(setting, previous);
    }
  }

  private static void restore(String name, String value) {
    if (value == null) System.clearProperty(name);
    else System.setProperty(name, value);
  }
}
