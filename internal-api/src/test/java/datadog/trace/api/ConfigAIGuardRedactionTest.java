package datadog.trace.api;

import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_REDACTION_ENABLED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class ConfigAIGuardRedactionTest {

  @Test
  void redactionIsEnabledByDefault() {
    assertTrue(Config.get().isAiGuardRedactionEnabled());
  }

  @Test
  void killSwitchDisablesRedactionViaSystemProperty() {
    WithConfigExtension.injectSysConfig(AI_GUARD_REDACTION_ENABLED, "false");

    assertFalse(Config.get().isAiGuardRedactionEnabled());
  }

  @Test
  void killSwitchDisablesRedactionViaEnvironmentVariable() {
    WithConfigExtension.injectEnvConfig("DD_AI_GUARD_REDACTION_ENABLED", "false", false);

    assertFalse(Config.get().isAiGuardRedactionEnabled());
  }

  @Test
  void killSwitchAcceptsNumericAndMixedCaseValues() {
    WithConfigExtension.injectSysConfig(AI_GUARD_REDACTION_ENABLED, "0");
    assertFalse(Config.get().isAiGuardRedactionEnabled());

    WithConfigExtension.injectSysConfig(AI_GUARD_REDACTION_ENABLED, "FALSE");
    assertFalse(Config.get().isAiGuardRedactionEnabled());

    WithConfigExtension.injectSysConfig(AI_GUARD_REDACTION_ENABLED, "True");
    assertTrue(Config.get().isAiGuardRedactionEnabled());
  }

  /**
   * Invalid boolean values resolve to {@code false} rather than to the configured default: see the
   * backward-compatibility branch in {@code ConfigProvider#get}. A typo therefore turns redaction
   * off, even though it defaults to on.
   */
  @Test
  void unparseableValueDisablesRedaction() {
    WithConfigExtension.injectSysConfig(AI_GUARD_REDACTION_ENABLED, "not-a-boolean");

    assertFalse(Config.get().isAiGuardRedactionEnabled());
  }
}
