package datadog.trace.api;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_CACHE_CONFIG;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_MAP_PER_STORE;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    "scenario                                  | names                      | defaultEnabled | expected",
    "no names, default enabled                 | []                         | true           | true    ",
    "no names, default disabled                | []                         | false          | false   ",
    "invalid, default enabled                  | [invalid]                  | true           | true    ",
    "invalid, default disabled                 | [invalid]                  | false          | false   ",
    "enabled by sys prop                       | [test-prop]                | false          | true    ",
    "enabled by env var                        | [test-env]                 | false          | true    ",
    "disabled by sys prop                      | [disabled-prop]            | true           | false   ",
    "disabled by env var                       | [disabled-env]             | true           | false   ",
    "other and enabled by sys prop             | [other, test-prop]         | false          | true    ",
    "other and enabled by env var              | [other, test-env]          | false          | true    ",
    "sys prop overrides env var                | [order]                    | false          | true    ",
    "enabled and disabled by sys prop          | [test-prop, disabled-prop] | false          | true    ",
    "disabled and enabled by env var           | [disabled-env, test-env]   | false          | true    ",
    "enabled and disabled by sys prop, default | [test-prop, disabled-prop] | true           | false   ",
    "disabled and enabled by env var, default  | [disabled-env, test-env]   | true           | false   ",
    "disabled by trace env var                 | [new-env]                  | true           | false   "
  })
  @WithConfig(key = "DD_INTEGRATION_ORDER_ENABLED", value = "false", env = true, addPrefix = false)
  @WithConfig(
      key = "DD_INTEGRATION_TEST_ENV_ENABLED",
      value = "true",
      env = true,
      addPrefix = false)
  @WithConfig(key = "DD_TRACE_NEW_ENV_ENABLED", value = "false", env = true, addPrefix = false)
  @WithConfig(
      key = "DD_INTEGRATION_DISABLED_ENV_ENABLED",
      value = "false",
      env = true,
      addPrefix = false)
  @WithConfig(key = "dd.integration.order.enabled", value = "true")
  @WithConfig(key = "dd.integration.test-prop.enabled", value = "true")
  @WithConfig(key = "dd.integration.disabled-prop.enabled", value = "false")
  @WithConfig(
      key = "DD_INTEGRATION_ORDER_MATCHING_SHORTCUT_ENABLED",
      value = "false",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "DD_INTEGRATION_TEST_ENV_MATCHING_SHORTCUT_ENABLED",
      value = "true",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "DD_INTEGRATION_NEW_ENV_MATCHING_SHORTCUT_ENABLED",
      value = "false",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "DD_INTEGRATION_DISABLED_ENV_MATCHING_SHORTCUT_ENABLED",
      value = "false",
      env = true,
      addPrefix = false)
  @WithConfig(key = "dd.integration.order.matching.shortcut.enabled", value = "true")
  @WithConfig(key = "dd.integration.test-prop.matching.shortcut.enabled", value = "true")
  @WithConfig(key = "dd.integration.disabled-prop.matching.shortcut.enabled", value = "false")
  void verifyIntegrationConfig(List<String> names, boolean defaultEnabled, boolean expected) {
    TreeSet<String> integrationNames = new TreeSet<>(names);

    assertEquals(
        expected, InstrumenterConfig.get().isIntegrationEnabled(integrationNames, defaultEnabled));
    assertEquals(
        expected,
        InstrumenterConfig.get()
            .isIntegrationShortcutMatchingEnabled(integrationNames, defaultEnabled));
  }

  private static boolean randomIntegrationEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(singletonList("random"), true);
  }

  @Test
  void verifyIntegrationEnabledHierarchy() {
    // the below should have no effect
    injectEnvConfig("DD_RANDOM_ENABLED", "false", false);
    injectSysConfig("dd.random.enabled", "false", false);
    assertTrue(randomIntegrationEnabled());

    injectEnvConfig("DD_INTEGRATION_RANDOM_ENABLED", "false", false);
    assertFalse(randomIntegrationEnabled());

    injectEnvConfig("DD_TRACE_INTEGRATION_RANDOM_ENABLED", "true", false);
    assertTrue(randomIntegrationEnabled());

    injectEnvConfig("DD_TRACE_RANDOM_ENABLED", "false", false);
    assertFalse(randomIntegrationEnabled());

    // assert all system properties take precedence over all env vars
    injectSysConfig("dd.integration.random.enabled", "true", false);
    assertTrue(randomIntegrationEnabled());

    injectSysConfig("dd.trace.integration.random.enabled", "false", false);
    assertFalse(randomIntegrationEnabled());

    injectSysConfig("dd.trace.random.enabled", "true", false);
    assertTrue(randomIntegrationEnabled());
  }

  @TableTest({
    "scenario | preset  | outlining",
    "large    | LARGE   | true     ",
    "small    | SMALL   | true     ",
    "default  | DEFAULT | true     ",
    "legacy   | LEGACY  | false    "
  })
  void validResolverPresets(String preset, boolean outlining) {
    injectSysConfig(RESOLVER_CACHE_CONFIG, preset);

    assertEquals(outlining, InstrumenterConfig.get().isResolverOutliningEnabled());
  }

  @ParameterizedTest(name = "preset=''{0}''")
  @ValueSource(strings = {"INVALID", ""})
  void invalidResolverPresets(String preset) {
    injectSysConfig(RESOLVER_CACHE_CONFIG, preset);

    assertTrue(InstrumenterConfig.get().isResolverOutliningEnabled());
  }

  @Test
  void sharedContextStorageIsDefaultFromJava25() {
    assertEquals(!isJavaVersionAtLeast(25), InstrumenterConfig.get().isRuntimeContextMapPerStore());
  }

  @TableTest({
    "scenario                            | property | environment | expected",
    "Enable map per store with property  | true     |             | true    ",
    "Disable map per store with property | false    |             | false   ",
    "Enable map per store with env       |          | true        | true    ",
    "Disable map per store with env      |          | false       | false   ",
    "Property true overrides env false   | true     | false       | true    ",
    "Property false overrides env true   | false    | true        | false   "
  })
  void contextMapPerStoreConfiguration(String property, String environment, boolean expected) {
    if (environment != null) {
      injectEnvConfig("DD_TRACE_RUNTIME_CONTEXT_MAP_PER_STORE", environment);
    }
    if (property != null) {
      injectSysConfig(RUNTIME_CONTEXT_MAP_PER_STORE, property);
    }

    assertEquals(expected, InstrumenterConfig.get().isRuntimeContextMapPerStore());
  }

  @TableTest({
    "scenario | input    | expected        ",
    "unset    |          | ENABLED_INACTIVE",
    "empty    | ''       | ENABLED_INACTIVE",
    "bad      | bad      | FULLY_DISABLED  ",
    "false    | false    | FULLY_DISABLED  ",
    "0        | 0        | FULLY_DISABLED  ",
    "true     | true     | FULLY_ENABLED   ",
    "1        | 1        | FULLY_ENABLED   ",
    "inactive | inactive | ENABLED_INACTIVE"
  })
  void appsecEnabled(String input, ProductActivation expected) {
    if (input != null) {
      injectSysConfig(APPSEC_ENABLED, input);
    }

    assertEquals(expected, InstrumenterConfig.get().getAppSecActivation());
  }

  @TableTest({
    "scenario | input    | expected        ",
    "unset    |          | FULLY_DISABLED  ",
    "empty    | ''       | FULLY_DISABLED  ",
    "bad      | bad      | FULLY_DISABLED  ",
    "false    | false    | FULLY_DISABLED  ",
    "0        | 0        | FULLY_DISABLED  ",
    "true     | true     | FULLY_ENABLED   ",
    "1        | 1        | FULLY_ENABLED   ",
    "inactive | inactive | ENABLED_INACTIVE"
  })
  void iastEnabled(String input, ProductActivation expected) {
    if (input != null) {
      injectSysConfig(IAST_ENABLED, input);
    }

    assertEquals(expected, InstrumenterConfig.get().getIastActivation());
  }
}
