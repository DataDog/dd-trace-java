package datadog.trace.api.featureflag.config;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_OFFLINE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.isSupportedConfigurationSource;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.resolveConfigurationSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeatureFlaggingConfigTest {

  @Test
  void appliesConfigurationPrecedence() {
    assertEquals(CONFIGURATION_SOURCE_AGENTLESS, resolveConfigurationSource(null, null, null));
    assertEquals(CONFIGURATION_SOURCE_AGENTLESS, resolveConfigurationSource(null, " ", null));
    assertEquals(CONFIGURATION_SOURCE_REMOTE_CONFIG, resolveConfigurationSource(null, null, true));
    assertEquals(CONFIGURATION_SOURCE_OFFLINE, resolveConfigurationSource(null, null, false));
    assertEquals(
        CONFIGURATION_SOURCE_AGENTLESS, resolveConfigurationSource(null, "agentless", true));
    assertEquals(
        CONFIGURATION_SOURCE_REMOTE_CONFIG,
        resolveConfigurationSource(null, " remote_CONFIG ", false));
    assertEquals(
        CONFIGURATION_SOURCE_OFFLINE, resolveConfigurationSource(false, "agentless", true));
    assertEquals(CONFIGURATION_SOURCE_OFFLINE, resolveConfigurationSource(null, "invalid", null));
    assertEquals(CONFIGURATION_SOURCE_OFFLINE, resolveConfigurationSource(null, "offline", null));
  }

  @Test
  void recognizesSupportedExplicitSources() {
    assertTrue(isSupportedConfigurationSource(null));
    assertTrue(isSupportedConfigurationSource(" "));
    assertTrue(isSupportedConfigurationSource("agentless"));
    assertTrue(isSupportedConfigurationSource(" REMOTE_CONFIG "));
    assertTrue(isSupportedConfigurationSource("OFFLINE"));
    assertFalse(isSupportedConfigurationSource("invalid"));
  }
}
