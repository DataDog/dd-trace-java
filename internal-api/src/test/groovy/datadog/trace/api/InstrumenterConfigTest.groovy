package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class InstrumenterConfigTest extends DDSpecification {

  def "verify integration config"() {
    setup:
    environmentVariables.set("DD_INTEGRATION_ORDER_ENABLED", "false")
    environmentVariables.set("DD_INTEGRATION_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_INTEGRATION_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.integration.order.enabled", "true")
    System.setProperty("dd.integration.test-prop.enabled", "true")
    System.setProperty("dd.integration.disabled-prop.enabled", "false")

    environmentVariables.set("DD_INTEGRATION_ORDER_MATCHING_SHORTCUT_ENABLED", "false")
    environmentVariables.set("DD_INTEGRATION_TEST_ENV_MATCHING_SHORTCUT_ENABLED", "true")
    environmentVariables.set("DD_INTEGRATION_DISABLED_ENV_MATCHING_SHORTCUT_ENABLED", "false")

    System.setProperty("dd.integration.order.matching.shortcut.enabled", "true")
    System.setProperty("dd.integration.test-prop.matching.shortcut.enabled", "true")
    System.setProperty("dd.integration.disabled-prop.matching.shortcut.enabled", "false")

    expect:
    InstrumenterConfig.get().isIntegrationEnabled(integrationNames, defaultEnabled) == expected
    InstrumenterConfig.get().isIntegrationShortcutMatchingEnabled(integrationNames, defaultEnabled) == expected

    where:
    // spotless:off
    names                          | defaultEnabled | expected
    []                             | true           | true
    []                             | false          | false
    ["invalid"]                    | true           | true
    ["invalid"]                    | false          | false
    ["test-prop"]                  | false          | true
    ["test-env"]                   | false          | true
    ["disabled-prop"]              | true           | false
    ["disabled-env"]               | true           | false
    ["other", "test-prop"]         | false          | true
    ["other", "test-env"]          | false          | true
    ["order"]                      | false          | true
    ["test-prop", "disabled-prop"] | false          | true
    ["disabled-env", "test-env"]   | false          | true
    ["test-prop", "disabled-prop"] | true           | false
    ["disabled-env", "test-env"]   | true           | false
    // spotless:on

    integrationNames = new TreeSet<>(names)
  }

  def "valid resolver presets"() {
    setup:
    injectSysConfig("resolver.cache.config", preset)

    expect:
    InstrumenterConfig.get().resolverOutliningEnabled == outlining

    where:
    // spotless:off
    preset    | outlining
    'LARGE'   | true
    'SMALL'   | true
    'DEFAULT' | true
    'LEGACY'  | false
    // spotless:on
  }

  def "invalid resolver presets"() {
    setup:
    injectSysConfig("resolver.cache.config", preset)

    expect:
    InstrumenterConfig.get().resolverOutliningEnabled

    where:
    preset << ['INVALID', '']
  }
}
