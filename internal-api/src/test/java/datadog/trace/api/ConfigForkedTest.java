package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_RUNTIME_ID_ENABLED;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(WithConfigExtension.class)
class ConfigForkedTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {RUNTIME_ID_ENABLED, RUNTIME_METRICS_RUNTIME_ID_ENABLED})
  void randomRuntimeIdGenerationCanBeTurnedOff(String setting) {
    injectSysConfig(setting, "false");

    assertEquals("", Config.get().getRuntimeId());
  }
}
