package datadog.trace.api;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.tabletest.junit.TableTest;

class InstrumenterConfigFeatureFlaggingTest {

  @TableTest({
    "scenario                  | enabled | source          | legacy  | resolvedEnabled | resolvedSource",
    "defaults                  |         |                 |         | true            | AGENTLESS     ",
    "blank source              |         | ' '             |         | true            | AGENTLESS     ",
    "legacy enabled            |         |                 | 'true'  | true            | REMOTE_CONFIG ",
    "legacy disabled           |         |                 | 'false' | false           | UNSET         ",
    "source wins over legacy   |         | 'agentless'     | 'true'  | true            | AGENTLESS     ",
    "case insensitive source   |         | 'Remote_Config' | 'false' | true            | REMOTE_CONFIG ",
    "disabled wins over source | 'false' | 'agentless'     | 'true'  | false           | AGENTLESS     ",
    "disabled without source   | 'false' |                 |         | false           | UNSET         ",
    "unsupported source        |         | 'offline'       | 'true'  | false           | INVALID       ",
    "padded source             |         | ' agentless '   |         | false           | INVALID       ",
    "unset is not a value      |         | 'unset'         |         | false           | INVALID       "
  })
  void resolvesFeatureFlagging(
      final String enabled,
      final String source,
      final String legacy,
      final boolean resolvedEnabled,
      final FeatureFlaggingSource resolvedSource) {
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
}
