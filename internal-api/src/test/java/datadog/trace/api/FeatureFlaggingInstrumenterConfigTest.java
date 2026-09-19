package datadog.trace.api;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FeatureFlaggingInstrumenterConfigTest {

  @ParameterizedTest
  @MethodSource("configurations")
  void enablesInstrumentationOnlyForExplicitEnabledConfiguration(
      final String enabled,
      final String source,
      final String legacyEnabled,
      final boolean expected) {
    final Properties properties = new Properties();
    setIfPresent(properties, FEATURE_FLAGS_ENABLED, enabled);
    setIfPresent(properties, FEATURE_FLAGS_CONFIGURATION_SOURCE, source);
    setIfPresent(properties, EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED, legacyEnabled);

    final InstrumenterConfig config =
        new InstrumenterConfig(ConfigProvider.withPropertiesOverride(properties));

    assertEquals(expected, config.isFeatureFlaggingInstrumentationEnabled());
  }

  private static Stream<Arguments> configurations() {
    return Stream.of(
        Arguments.of(null, null, null, false),
        Arguments.of(null, "agentless", null, true),
        Arguments.of(null, "remote_config", null, true),
        Arguments.of("true", null, null, true),
        Arguments.of(null, null, "true", true),
        Arguments.of("false", "agentless", null, false),
        Arguments.of(null, "invalid", null, false),
        Arguments.of(null, null, "false", false));
  }

  private static void setIfPresent(
      final Properties properties, final String key, final String value) {
    if (value != null) {
      properties.setProperty(key, value);
    }
  }
}
