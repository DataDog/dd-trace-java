package datadog.trace.agent.test;

import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.EnvironmentVariables;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class DefaultInstrumenterForkedTest extends DDJavaSpecification {
  static {
    TypePoolFacade.registerAsSupplier();
    DDElementMatchers.registerAsSupplier();
  }

  private static StrictnessPolicy previousStrictnessPolicy;

  @BeforeAll
  static void setupStrictness() {
    previousStrictnessPolicy = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
  }

  @AfterAll
  static void restoreStrictness() {
    ConfigHelper.get().setConfigInversionStrict(previousStrictnessPolicy);
  }

  @Test
  void defaultEnabled() {
    TestDefaultInstrumenter target = new TestDefaultInstrumenter("test");

    assertTrue(target.isEnabled());
  }

  @TableTest({
    "scenario         | enabled",
    "default enabled  | true   ",
    "default disabled | false  "
  })
  void defaultEnabledOverride(boolean enabled) {
    TestDefaultInstrumenter target = instrumenterWithDefault(enabled);

    assertEquals(enabled, target.isEnabled());
  }

  @TableTest({
    "scenario          | enabled",
    "override enabled  | true   ",
    "override disabled | false  "
  })
  void defaultDisabledCanOverrideToEnabled(boolean enabled) {
    injectSysConfig("integration.test.enabled", String.valueOf(enabled));
    TestDefaultInstrumenter target = instrumenterWithDefault(false);

    assertEquals(enabled, target.isEnabled());
  }

  @TableTest({
    "scenario | value | enabled",
    "true     | true  | true   ",
    "false    | false | false  ",
    "invalid  | asdf  | false  "
  })
  void configureDefaultSysProp(String value, boolean enabled) {
    injectSysConfig(INTEGRATIONS_ENABLED, value);

    // when
    TestDefaultInstrumenter target = new TestDefaultInstrumenter("test");

    // then
    assertEquals(enabled, target.isEnabled());
  }

  @TableTest({
    "scenario | value | enabled",
    "true     | true  | true   ",
    "false    | false | false  ",
    "invalid  | asdf  | false  "
  })
  void configureDefaultEnvVar(String value, boolean enabled) {
    injectEnvConfig("DD_INTEGRATIONS_ENABLED", value);
    TestDefaultInstrumenter target = new TestDefaultInstrumenter("test");

    assertEquals(enabled, target.isEnabled());
  }

  @TableTest({
    "scenario        | value           | enabled | name        | altName        ",
    "test            | test            | true    | test        | asdf           ",
    "duplicate       | duplicate       | true    | duplicate   | duplicate      ",
    "bad             | bad             | false   | not         | valid          ",
    "altTest         | altTest         | true    | asdf        | altTest        ",
    "dash-test       | dash-test       | true    | dash-test   | asdf           ",
    "underscore_test | underscore_test | true    | asdf        | underscore_test",
    "period.test     | period.test     | true    | period.test | asdf           "
  })
  @WithConfig(key = INTEGRATIONS_ENABLED, value = "false")
  void configureSysPropEnabledWhenDefaultIsDisabled(
      String value, boolean enabled, String name, String altName) {
    injectSysConfig("integration." + value + ".enabled", "true");
    TestDefaultInstrumenter target = new TestDefaultInstrumenter(name, altName);

    assertEquals(enabled, target.isEnabled());
  }

  @TableTest({
    "scenario        | value           | enabled | name        | altName        ",
    "TEST            | TEST            | true    | test        | asdf           ",
    "DUPLICATE       | DUPLICATE       | true    | duplicate   | duplicate      ",
    "BAD             | BAD             | false   | not         | valid          ",
    "ALTTEST         | ALTTEST         | true    | asdf        | altTest        ",
    "DASH_TEST       | DASH_TEST       | true    | dash-test   | asdf           ",
    "UNDERSCORE_TEST | UNDERSCORE_TEST | true    | asdf        | underscore_test",
    "PERIOD_TEST     | PERIOD_TEST     | true    | period.test | asdf           "
  })
  @WithConfig(key = "DD_INTEGRATIONS_ENABLED", value = "false", env = true)
  void configureEnvVarEnabledWhenDefaultIsDisabled(
      String value, boolean enabled, String name, String altName) {
    String envVarName = "DD_INTEGRATION_" + value + "_ENABLED";
    injectEnvConfig(envVarName, "true");

    // when
    TestDefaultInstrumenter target = new TestDefaultInstrumenter(name, altName);

    // then
    assertEquals("true", EnvironmentVariables.get(envVarName));
    assertEquals(enabled, target.isEnabled());
  }

  private static TestDefaultInstrumenter instrumenterWithDefault(boolean defaultEnabled) {
    return new TestDefaultInstrumenter("test") {
      @Override
      protected boolean defaultEnabled() {
        return defaultEnabled;
      }
    };
  }

  static class TestDefaultInstrumenter extends InstrumenterModule.Tracing {

    TestDefaultInstrumenter(String instrumentationName) {
      super(instrumentationName);
    }

    TestDefaultInstrumenter(String instrumentationName, String additionalName) {
      super(instrumentationName, additionalName);
    }
  }
}
