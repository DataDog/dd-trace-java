package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class LogPatternConfigTest {
  @Test
  void injectionIsDisabledByDefault() {
    assertFalse(Config.get().isLogPatternInject());
  }

  @Test
  @WithConfig(key = "logs.pattern.inject", value = "true")
  void enablesInjectionWithSystemProperty() {
    assertTrue(Config.get().isLogPatternInject());
  }

  @Test
  @WithConfig(key = "LOGS_PATTERN_INJECT", value = "true", env = true)
  void enablesInjectionWithEnvironmentVariable() {
    assertTrue(Config.get().isLogPatternInject());
  }

  @Test
  @WithConfig(key = "LOGS_PATTERN_INJECT", value = "true", env = true)
  @WithConfig(key = "logs.pattern.inject", value = "false")
  void systemPropertyTakesPrecedence() {
    assertFalse(Config.get().isLogPatternInject());
  }
}
