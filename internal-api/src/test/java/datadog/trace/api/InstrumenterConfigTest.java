package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class InstrumenterConfigTest {

  private StrictnessPolicy strictness;

  @BeforeEach
  void setup() {
    strictness = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
  }

  @AfterEach
  void cleanup() {
    ConfigHelper.get().setConfigInversionStrict(strictness);
  }

  @TableTest({
    "scenario                                        | names                      | defaultEnabled | expected",
    "empty names, default enabled                    | []                         | true           | true    ",
    "empty names, default disabled                   | []                         | false          | false   ",
    "invalid name, default enabled                   | [invalid]                  | true           | true    ",
    "invalid name, default disabled                  | [invalid]                  | false          | false   ",
    "test-prop env var enabled overrides default off | [test-prop]                | false          | true    ",
    "test-env env var enabled overrides default off  | [test-env]                 | false          | true    ",
    "disabled-prop sys prop overrides default on     | [disabled-prop]            | true           | false   ",
    "disabled-env env var overrides default on       | [disabled-env]             | true           | false   ",
    "mixed names, test-prop wins                     | [other, test-prop]         | false          | true    ",
    "mixed names, test-env wins                      | [other, test-env]          | false          | true    ",
    "order enabled by both sys prop and env var      | [order]                    | false          | true    ",
    "test-prop and disabled-prop, default off        | [test-prop, disabled-prop] | false          | true    ",
    "disabled-env and test-env, default off          | [disabled-env, test-env]   | false          | true    ",
    "test-prop and disabled-prop, default on         | [test-prop, disabled-prop] | true           | false   ",
    "disabled-env and test-env, default on           | [disabled-env, test-env]   | true           | false   ",
    "new-env disabled overrides default on           | [new-env]                  | true           | false   "
  })
  @WithConfig(key = "INTEGRATION_ORDER_ENABLED", value = "false", env = true)
  @WithConfig(key = "INTEGRATION_TEST_ENV_ENABLED", value = "true", env = true)
  @WithConfig(key = "TRACE_NEW_ENV_ENABLED", value = "false", env = true)
  @WithConfig(key = "INTEGRATION_DISABLED_ENV_ENABLED", value = "false", env = true)
  @WithConfig(key = "INTEGRATION_ORDER_MATCHING_SHORTCUT_ENABLED", value = "false", env = true)
  @WithConfig(key = "INTEGRATION_TEST_ENV_MATCHING_SHORTCUT_ENABLED", value = "true", env = true)
  @WithConfig(key = "INTEGRATION_NEW_ENV_MATCHING_SHORTCUT_ENABLED", value = "false", env = true)
  @WithConfig(
      key = "INTEGRATION_DISABLED_ENV_MATCHING_SHORTCUT_ENABLED",
      value = "false",
      env = true)
  @WithConfig(key = "integration.order.enabled", value = "true")
  @WithConfig(key = "integration.test-prop.enabled", value = "true")
  @WithConfig(key = "integration.disabled-prop.enabled", value = "false")
  @WithConfig(key = "integration.order.matching.shortcut.enabled", value = "true")
  @WithConfig(key = "integration.test-prop.matching.shortcut.enabled", value = "true")
  @WithConfig(key = "integration.disabled-prop.matching.shortcut.enabled", value = "false")
  void verifyIntegrationConfig(List<String> names, boolean defaultEnabled, boolean expected) {
    Set<String> integrationNames = new TreeSet<>(names);
    assertEquals(
        expected, InstrumenterConfig.get().isIntegrationEnabled(integrationNames, defaultEnabled));
    assertEquals(
        expected,
        InstrumenterConfig.get()
            .isIntegrationShortcutMatchingEnabled(integrationNames, defaultEnabled));
  }

  private static boolean randomIntegrationEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singletonList("random"), true);
  }

  @Test
  void verifyIntegrationEnabledHierarchy() {
    // the below should have no effect
    WithConfigExtension.injectEnvConfig("RANDOM_ENABLED", "false");
    WithConfigExtension.injectSysConfig("random.enabled", "false");
    assertTrue(randomIntegrationEnabled());

    WithConfigExtension.injectEnvConfig("INTEGRATION_RANDOM_ENABLED", "false");
    assertFalse(randomIntegrationEnabled());

    WithConfigExtension.injectEnvConfig("TRACE_INTEGRATION_RANDOM_ENABLED", "true");
    assertTrue(randomIntegrationEnabled());

    WithConfigExtension.injectEnvConfig("TRACE_RANDOM_ENABLED", "false");
    assertFalse(randomIntegrationEnabled());

    // assert all system properties take precedence over all env vars
    WithConfigExtension.injectSysConfig("integration.random.enabled", "true");
    assertTrue(randomIntegrationEnabled());

    WithConfigExtension.injectSysConfig("trace.integration.random.enabled", "false");
    assertFalse(randomIntegrationEnabled());

    WithConfigExtension.injectSysConfig("trace.random.enabled", "true");
    assertTrue(randomIntegrationEnabled());
  }

  @TableTest({
    "scenario       | preset  | outlining",
    "large preset   | LARGE   | true     ",
    "small preset   | SMALL   | true     ",
    "default preset | DEFAULT | true     ",
    "legacy preset  | LEGACY  | false    "
  })
  void validResolverPresets(String preset, boolean outlining) {
    WithConfigExtension.injectSysConfig("resolver.cache.config", preset);

    assertEquals(outlining, InstrumenterConfig.get().isResolverOutliningEnabled());
  }

  @TableTest({"scenario       | preset ", "invalid preset | INVALID", "empty preset   | ''     "})
  void invalidResolverPresets(String preset) {
    WithConfigExtension.injectSysConfig("resolver.cache.config", preset);

    assertTrue(InstrumenterConfig.get().isResolverOutliningEnabled());
  }

  @TableTest({
    "scenario                          | input    | expected        ",
    "unset defaults to inactive        |          | ENABLED_INACTIVE",
    "empty string is inactive          | ''       | ENABLED_INACTIVE",
    "unparseable value disables        | bad      | FULLY_DISABLED  ",
    "explicit false disables           | false    | FULLY_DISABLED  ",
    "zero disables                     | 0        | FULLY_DISABLED  ",
    "explicit true enables             | true     | FULLY_ENABLED   ",
    "one enables                       | 1        | FULLY_ENABLED   ",
    "inactive keyword enables inactive | inactive | ENABLED_INACTIVE"
  })
  void appsecEnabled(String input, ProductActivation expected) {
    if (input != null) {
      WithConfigExtension.injectSysConfig("appsec.enabled", input);
    }

    assertEquals(expected, InstrumenterConfig.get().getAppSecActivation());
  }

  @TableTest({
    "scenario                          | input    | expected        ",
    "unset disables                    |          | FULLY_DISABLED  ",
    "empty string disables             | ''       | FULLY_DISABLED  ",
    "unparseable value disables        | bad      | FULLY_DISABLED  ",
    "explicit false disables           | false    | FULLY_DISABLED  ",
    "zero disables                     | 0        | FULLY_DISABLED  ",
    "explicit true enables             | true     | FULLY_ENABLED   ",
    "one enables                       | 1        | FULLY_ENABLED   ",
    "inactive keyword enables inactive | inactive | ENABLED_INACTIVE"
  })
  void iastEnabled(String input, ProductActivation expected) {
    if (input != null) {
      WithConfigExtension.injectSysConfig("iast.enabled", input);
    }

    assertEquals(expected, InstrumenterConfig.get().getIastActivation());
  }

  @TableTest({
    "scenario                | input | expected",
    "unset defaults to false |       | false   ",
    "explicit false disables | false | false   ",
    "explicit true enables   | true  | true    ",
    "one enables             | 1     | true    ",
    "zero disables           | 0     | false   "
  })
  void dataStreamsEnabled(String input, boolean expected) {
    if (input != null) {
      WithConfigExtension.injectSysConfig("data.streams.enabled", input);
    }

    assertEquals(expected, InstrumenterConfig.get().isDataStreamsEnabled());
  }

  @Test
  void dataStreamsEnabledDefaultsToFalse() {
    assertFalse(InstrumenterConfig.get().isDataStreamsEnabled());
  }

  @Test
  @WithConfig(key = "DATA_STREAMS_ENABLED", value = "true", env = true)
  void dataStreamsEnabledViaEnvVar() {
    assertTrue(InstrumenterConfig.get().isDataStreamsEnabled());
  }
}
