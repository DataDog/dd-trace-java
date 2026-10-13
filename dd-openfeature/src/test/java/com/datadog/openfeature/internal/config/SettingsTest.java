package com.datadog.openfeature.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.connector.ConfigurationSource;
import com.datadog.openfeature.internal.connector.Connector;
import com.datadog.openfeature.internal.connector.EventTransport;
import com.datadog.openfeature.internal.connector.HealthMetrics;
import com.datadog.openfeature.internal.connector.SpanEnricher;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SettingsTest {
  @Test
  void looksUpConnectorThenSystemPropertiesThenEnvironment() {
    final Map<String, String> connector = new HashMap<>();
    final Map<String, String> properties = new HashMap<>();
    final Map<String, String> environment = new HashMap<>();
    final Settings settings =
        new Settings(new SettingConnector(connector), properties::get, environment::get);

    assertNull(settings.env());
    environment.put("DD_ENV", "from-env");
    assertEquals("from-env", settings.env());
    properties.put("dd.env", "from-property");
    assertEquals("from-property", settings.env());
    connector.put("env", "from-agent");
    assertEquals("from-agent", settings.env());
  }

  @TableTest({
    "scenario          | key                                  | variable                               ",
    "dotted key        | 'feature.flags.enabled'              | 'DD_FEATURE_FLAGS_ENABLED'             ",
    "dashed key        | 'api-key'                            | 'DD_API_KEY'                           ",
    "deeply nested key | 'flagging.evaluation.counts.enabled' | 'DD_FLAGGING_EVALUATION_COUNTS_ENABLED'"
  })
  void mapsKeysToEnvironmentVariables(final String key, final String variable) {
    assertEquals(variable, Settings.environmentVariableName(key));
  }

  @TableTest({
    "scenario                         | enabled | source            | legacy  | resolved       ",
    "default                          |         |                   |         | 'agentless'    ",
    "explicit agentless               |         | 'agentless'       |         | 'agentless'    ",
    "explicit remote config           |         | 'remote_config'   |         | 'remote_config'",
    "normalized source                |         | ' Remote_Config ' |         | 'remote_config'",
    "unsupported source               |         | 'offline'         |         |                ",
    "blank source                     |         | ' '               |         | 'agentless'    ",
    "disabled wins over source        | 'false' | 'remote_config'   |         |                ",
    "enabled keeps default            | 'true'  |                   |         | 'agentless'    ",
    "legacy enabled selects rc        |         |                   | 'true'  | 'remote_config'",
    "legacy disabled disables         |         |                   | 'false' |                ",
    "explicit source wins over legacy |         | 'agentless'       | 'false' | 'agentless'    ",
    "invalid enabled reads as false   | 'maybe' |                   |         |                ",
    "numeric disabled                 | '0'     |                   |         |                "
  })
  void resolvesConfigurationSource(
      final String enabled, final String source, final String legacy, final String resolved) {
    final Map<String, String> properties = new HashMap<>();
    putIfNotNull(properties, "dd.feature.flags.enabled", enabled);
    putIfNotNull(properties, "dd.feature.flags.configuration.source", source);
    putIfNotNull(properties, "dd.experimental.flagging.provider.enabled", legacy);
    final Settings settings = new Settings(Connector.NONE, properties::get, name -> null);

    assertEquals(resolved, settings.configurationSource());
    assertEquals(resolved != null, settings.isEnabled());
  }

  @Test
  void appliesDefaults() {
    final Settings settings = TestSettings.of();

    assertEquals("datadoghq.com", settings.site());
    assertNull(settings.apiKey());
    assertNull(settings.service());
    assertNull(settings.version());
    assertNull(settings.agentlessBaseUrl());
    assertEquals(30, settings.agentlessPollIntervalSeconds());
    assertEquals(5, settings.agentlessRequestTimeoutSeconds());
    assertFalse(settings.isSpanEnrichmentEnabled());
    assertTrue(settings.isEvaluationCountsEnabled());
  }

  @TableTest({
    "scenario | value | interval",
    "valid    | '10'  | 10      ",
    "zero     | '0'   | 30      ",
    "negative | '-5'  | 30      ",
    "invalid  | 'ten' | 30      ",
    "blank    | ' '   | 30      "
  })
  void fallsBackToDefaultPollIntervalOnInvalidValues(final String value, final int interval) {
    assertEquals(
        interval,
        TestSettings.of("feature.flags.configuration.source.agentless.poll.interval.seconds", value)
            .agentlessPollIntervalSeconds());
  }

  @Test
  void trimsAndIgnoresBlankValues() {
    final Settings settings =
        TestSettings.of(
            "api-key", "  secret  ",
            "site", " ",
            "feature.flags.configuration.source.agentless.base.url", "   ");

    assertEquals("secret", settings.apiKey());
    assertEquals("datadoghq.com", settings.site());
    assertNull(settings.agentlessBaseUrl());
  }

  @Test
  void readsToggles() {
    final Settings settings =
        TestSettings.of(
            "experimental.flagging.provider.span.enrichment.enabled", "true",
            "flagging.evaluation.counts.enabled", "false");

    assertTrue(settings.isSpanEnrichmentEnabled());
    assertFalse(settings.isEvaluationCountsEnabled());
  }

  private static void putIfNotNull(
      final Map<String, String> map, final String key, final String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  /** A connector only providing settings. */
  private static final class SettingConnector implements Connector {
    private final Map<String, String> settings;

    SettingConnector(final Map<String, String> settings) {
      this.settings = settings;
    }

    @Override
    public String setting(final String key) {
      return this.settings.get(key);
    }

    @Override
    public ConfigurationSource remoteConfiguration() {
      return null;
    }

    @Override
    public EventTransport eventProxy() {
      return null;
    }

    @Override
    public HealthMetrics healthMetrics() {
      return HealthMetrics.NOOP;
    }

    @Override
    public SpanEnricher spanEnricher() {
      return SpanEnricher.NOOP;
    }
  }
}
