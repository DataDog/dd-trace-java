package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class TraceInstrumentationConfigTest extends DDSpecification {

  def "verify integration config"() {
    setup:
    environmentVariables.set("DD_INTEGRATION_ORDER_ENABLED", "false")
    environmentVariables.set("DD_INTEGRATION_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_INTEGRATION_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.integration.order.enabled", "true")
    System.setProperty("dd.integration.test-prop.enabled", "true")
    System.setProperty("dd.integration.disabled-prop.enabled", "false")

    expect:
    Config.get().isIntegrationEnabled(integrationNames, defaultEnabled) == expected

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

  def "verify rule config #name"() {
    setup:
    environmentVariables.set("DD_TRACE_TEST_ENABLED", "true")
    environmentVariables.set("DD_TRACE_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_TRACE_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.trace.test.enabled", "false")
    System.setProperty("dd.trace.test-prop.enabled", "true")
    System.setProperty("dd.trace.disabled-prop.enabled", "false")

    expect:
    Config.get().isRuleEnabled(name) == enabled

    where:
    // spotless:off
    name            | enabled
    ""              | true
    "invalid"       | true
    "test-prop"     | true
    "Test-Prop"     | true
    "test-env"      | true
    "Test-Env"      | true
    "test"          | false
    "TEST"          | false
    "disabled-prop" | false
    "Disabled-Prop" | false
    "disabled-env"  | false
    "Disabled-Env"  | false
    // spotless:on
  }
  def "verify integration jmxfetch config"() {
    setup:
    environmentVariables.set("DD_JMXFETCH_ORDER_ENABLED", "false")
    environmentVariables.set("DD_JMXFETCH_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_JMXFETCH_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.jmxfetch.order.enabled", "true")
    System.setProperty("dd.jmxfetch.test-prop.enabled", "true")
    System.setProperty("dd.jmxfetch.disabled-prop.enabled", "false")

    expect:
    Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled) == expected

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

  def "verify integration trace analytics config"() {
    setup:
    environmentVariables.set("DD_ORDER_ANALYTICS_ENABLED", "false")
    environmentVariables.set("DD_TEST_ENV_ANALYTICS_ENABLED", "true")
    environmentVariables.set("DD_DISABLED_ENV_ANALYTICS_ENABLED", "false")
    // trace prefix form should take precedence over the old non-prefix form
    environmentVariables.set("DD_ALIAS_ENV_ANALYTICS_ENABLED", "false")
    environmentVariables.set("DD_TRACE_ALIAS_ENV_ANALYTICS_ENABLED", "true")

    System.setProperty("dd.order.analytics.enabled", "true")
    System.setProperty("dd.test-prop.analytics.enabled", "true")
    System.setProperty("dd.disabled-prop.analytics.enabled", "false")
    // trace prefix form should take precedence over the old non-prefix form
    System.setProperty("dd.alias-prop.analytics.enabled", "false")
    System.setProperty("dd.trace.alias-prop.analytics.enabled", "true")

    expect:
    Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled) == expected

    where:
    // spotless:off
    names                           | defaultEnabled | expected
    []                              | true           | true
    []                              | false          | false
    ["invalid"]                     | true           | true
    ["invalid"]                     | false          | false
    ["test-prop"]                   | false          | true
    ["test-env"]                    | false          | true
    ["disabled-prop"]               | true           | false
    ["disabled-env"]                | true           | false
    ["other", "test-prop"]          | false          | true
    ["other", "test-env"]           | false          | true
    ["order"]                       | false          | true
    ["test-prop", "disabled-prop"]  | false          | true
    ["disabled-env", "test-env"]    | false          | true
    ["test-prop", "disabled-prop"]  | true           | false
    ["disabled-env", "test-env"]    | true           | false
    ["alias-prop", "disabled-prop"] | false          | true
    ["disabled-env", "alias-env"]   | false          | true
    ["alias-prop", "disabled-prop"] | true           | false
    ["disabled-env", "alias-env"]   | true           | false
    // spotless:on

    integrationNames = new TreeSet<>(names)
  }
}
