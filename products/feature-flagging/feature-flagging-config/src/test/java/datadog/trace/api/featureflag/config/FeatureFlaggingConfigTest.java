package datadog.trace.api.featureflag.config;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.isSupportedConfigurationSource;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.resolveConfiguration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeatureFlaggingConfigTest {

  @Test
  void appliesConfigurationPrecedence() {
    assertResolution(true, CONFIGURATION_SOURCE_AGENTLESS, null, null, null);
    assertResolution(true, CONFIGURATION_SOURCE_AGENTLESS, null, " ", null);
    assertResolution(true, CONFIGURATION_SOURCE_REMOTE_CONFIG, null, null, true);
    assertResolution(false, null, null, null, false);
    assertResolution(true, CONFIGURATION_SOURCE_AGENTLESS, null, "agentless", true);
    assertResolution(true, CONFIGURATION_SOURCE_REMOTE_CONFIG, null, " remote_CONFIG ", false);
    assertResolution(false, CONFIGURATION_SOURCE_AGENTLESS, false, "agentless", true);
    assertResolution(false, "invalid", null, "invalid", null);
    assertResolution(false, "offline", null, " OFFLINE ", true);
  }

  @Test
  void recognizesSupportedExplicitSources() {
    assertTrue(isSupportedConfigurationSource(null));
    assertTrue(isSupportedConfigurationSource(" "));
    assertTrue(isSupportedConfigurationSource("agentless"));
    assertTrue(isSupportedConfigurationSource(" REMOTE_CONFIG "));
    assertFalse(isSupportedConfigurationSource("invalid"));
    assertFalse(isSupportedConfigurationSource("offline"));
  }

  private static void assertResolution(
      final boolean enabled,
      final String source,
      final Boolean providerEnabled,
      final String explicitSource,
      final Boolean legacyProviderEnabled) {
    final FeatureFlaggingConfig.Resolution resolution =
        resolveConfiguration(providerEnabled, explicitSource, legacyProviderEnabled);

    assertEquals(enabled, resolution.isEnabled());
    if (source == null) {
      assertNull(resolution.getSource());
    } else {
      assertEquals(source, resolution.getSource());
    }
  }
}
