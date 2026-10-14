package datadog.trace.api;

import static datadog.trace.api.InstrumenterConfig.isSupportedFeatureFlaggingSource;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.tabletest.junit.TableTest;

class InstrumenterConfigFeatureFlaggingTest {

  @TableTest({
    "scenario                  | enabled | source            | legacy  | resolvedEnabled | resolvedSource ",
    "defaults                  |         |                   |         | true            | 'agentless'    ",
    "blank source              |         | ' '               |         | true            | 'agentless'    ",
    "legacy enabled            |         |                   | 'true'  | true            | 'remote_config'",
    "legacy disabled           |         |                   | 'false' | false           |                ",
    "source wins over legacy   |         | 'agentless'       | 'true'  | true            | 'agentless'    ",
    "normalized source         |         | ' remote_CONFIG ' | 'false' | true            | 'remote_config'",
    "disabled wins over source | 'false' | 'agentless'       | 'true'  | false           | 'agentless'    ",
    "disabled without source   | 'false' |                   |         | false           |                ",
    "unsupported source        |         | 'invalid'         |         | false           | 'invalid'      ",
    "unsupported normalized    |         | ' OFFLINE '       | 'true'  | false           | 'offline'      "
  })
  void resolvesFeatureFlagging(
      final String enabled,
      final String source,
      final String legacy,
      final boolean resolvedEnabled,
      final String resolvedSource) {
    final Properties properties = new Properties();
    if (enabled != null) {
      properties.setProperty(FEATURE_FLAGS_ENABLED, enabled);
    }
    if (source != null) {
      properties.setProperty(FEATURE_FLAGS_CONFIGURATION_SOURCE, source);
    }
    if (legacy != null) {
      properties.setProperty(EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED, legacy);
    }

    final InstrumenterConfig config =
        new InstrumenterConfig(ConfigProvider.withPropertiesOverride(properties));

    assertEquals(resolvedEnabled, config.isFeatureFlaggingEnabled());
    assertEquals(resolvedSource, config.getFeatureFlaggingConfigurationSource());
  }

  @TableTest({
    "scenario      | source          | supported",
    "absent        |                 | true     ",
    "agentless     | 'agentless'     | true     ",
    "remote config | 'remote_config' | true     ",
    "unsupported   | 'offline'       | false    "
  })
  void recognizesSupportedSources(final String source, final boolean supported) {
    assertEquals(supported, isSupportedFeatureFlaggingSource(source));
  }
}
