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

  def "appsec enabled = #input"() {
    setup:
    if (input != null) {
      injectSysConfig("appsec.enabled", input)
    }

    expect:
    InstrumenterConfig.get().getAppSecActivation() == expected

    where:
    input      | expected
    null       | ProductActivation.ENABLED_INACTIVE
    ""         | ProductActivation.ENABLED_INACTIVE
    "bad"      | ProductActivation.FULLY_DISABLED
    "false"    | ProductActivation.FULLY_DISABLED
    "0"        | ProductActivation.FULLY_DISABLED
    "true"     | ProductActivation.FULLY_ENABLED
    "1"        | ProductActivation.FULLY_ENABLED
    "inactive" | ProductActivation.ENABLED_INACTIVE
  }

  def "iast enabled = #input"() {
    setup:
    if (input != null) {
      injectSysConfig("iast.enabled", input)
    }

    expect:
    InstrumenterConfig.get().getIastActivation() == expected

    where:
    input      | expected
    null       | ProductActivation.FULLY_DISABLED
    ""         | ProductActivation.FULLY_DISABLED
    "bad"      | ProductActivation.FULLY_DISABLED
    "false"    | ProductActivation.FULLY_DISABLED
    "0"        | ProductActivation.FULLY_DISABLED
    "true"     | ProductActivation.FULLY_ENABLED
    "1"        | ProductActivation.FULLY_ENABLED
    "inactive" | ProductActivation.ENABLED_INACTIVE
  }
}
