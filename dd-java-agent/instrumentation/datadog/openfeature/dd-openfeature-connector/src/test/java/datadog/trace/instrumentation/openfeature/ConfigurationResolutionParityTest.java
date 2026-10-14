package datadog.trace.instrumentation.openfeature;

import static datadog.trace.api.config.FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED;
import static datadog.trace.api.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.connector.Connector;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

/**
 * Guards against drift between the agent ({@link InstrumenterConfig}) and the SDK ({@link
 * Settings}) resolution of the Feature Flags product switch and configuration source. Both sides
 * read the same raw settings, keyed by the agent setting names.
 */
@ExtendWith(WithConfigExtension.class)
class ConfigurationResolutionParityTest {
  @TableTest({
    "Scenario                         | Enabled | Source           | Legacy | Expected     ",
    "defaults                         |         |                  |        | agentless    ",
    "enabled                          | true    |                  |        | agentless    ",
    "enabled as number                | 1       |                  |        | agentless    ",
    "disabled                         | false   |                  |        |              ",
    "disabled as number               | 0       |                  |        |              ",
    "disabled wins over source        | false   | remote_config    |        |              ",
    "remote config source             |         | remote_config    |        | remote_config",
    "agentless source                 |         | agentless        |        | agentless    ",
    "source is normalized             |         | ' Remote_Config' |        | remote_config",
    "unsupported source               |         | offline          |        |              ",
    "legacy enabled                   |         |                  | true   | remote_config",
    "legacy disabled                  |         |                  | false  |              ",
    "source wins over legacy          |         | agentless        | true   | agentless    ",
    "source wins over legacy disabled |         | remote_config    | false  | remote_config",
    "enabled with legacy disabled     | true    |                  | false  |              ",
    "uppercase boolean                | TRUE    |                  | TRUE   | remote_config",
    "invalid boolean reads as false   | maybe   |                  |        |              ",
    "invalid legacy reads as false    |         |                  | maybe  |              "
  })
  void agentAndSdkResolveTheSameConfiguration(
      final String enabled, final String source, final String legacy, final String expected) {
    final Map<String, String> settings = new HashMap<>();
    putIfNotNull(settings, FEATURE_FLAGS_ENABLED, enabled);
    putIfNotNull(settings, FEATURE_FLAGS_CONFIGURATION_SOURCE, source);
    putIfNotNull(settings, EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED, legacy);

    assertEquals(expected, agentSource(settings), "agent resolution");
    assertEquals(expected, sdkSource(settings), "SDK resolution");
  }

  /** Resolves the source like the agent, through {@link InstrumenterConfig}. */
  private static String agentSource(final Map<String, String> settings) {
    settings.forEach(WithConfigExtension::injectSysConfig);
    final InstrumenterConfig config = InstrumenterConfig.get();
    return config.isFeatureFlaggingEnabled()
        ? config.getFeatureFlaggingConfigurationSource()
        : null;
  }

  /**
   * Resolves the source like the SDK. Missing settings are reported as blank, so the SDK does not
   * fall back to the system properties and environment of the test JVM.
   */
  private static String sdkSource(final Map<String, String> settings) {
    final Connector connector = mock(Connector.class);
    when(connector.setting(anyString()))
        .thenAnswer(invocation -> settings.getOrDefault(invocation.<String>getArgument(0), ""));
    return Settings.load(connector).configurationSource();
  }

  private static void putIfNotNull(
      final Map<String, String> map, final String key, final String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
