package datadog.trace.agent.test

import datadog.environment.EnvironmentVariables
import datadog.trace.agent.tooling.InstrumenterModule
import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade
import datadog.trace.test.util.DDSpecification

class DefaultInstrumenterForkedTest extends DDSpecification {
  static {
    TypePoolFacade.registerAsSupplier()
    DDElementMatchers.registerAsSupplier()
  }

  def "default enabled"() {
    setup:
    def target = new TestDefaultInstrumenter("test")

    expect:
    target.enabled
  }

  def "default enabled override #enabled"() {
    expect:
    target.enabled == enabled

    where:
    enabled | target
    true    | new TestDefaultInstrumenter("test") {
        @Override
        protected boolean defaultEnabled() {
          return true
        }
      }
    false   | new TestDefaultInstrumenter("test") {
        @Override
        protected boolean defaultEnabled() {
          return false
        }
      }
  }

  def "default disabled can override to enabled"() {
    setup:
    injectSysConfig("integration.test.enabled", "$enabled")
    def target = new TestDefaultInstrumenter("test") {
        @Override
        protected boolean defaultEnabled() {
          return false
        }
      }

    expect:
    target.enabled == enabled

    where:
    enabled << [true, false]
  }

  def "configure default sys prop as #value"() {
    setup:
    injectSysConfig("integrations.enabled", value)

    when:
    def target = new TestDefaultInstrumenter("test")

    then:
    target.enabled == enabled

    where:
    value   | enabled
    "true"  | true
    "false" | false
    "asdf"  | false
  }

  def "configure default env var as #value"() {
    setup:
    injectEnvConfig("DD_INTEGRATIONS_ENABLED", value)
    def target = new TestDefaultInstrumenter("test")

    expect:
    target.enabled == enabled

    where:
    value   | enabled
    "true"  | true
    "false" | false
    "asdf"  | false
  }

  def "configure sys prop enabled for #value when default is disabled"() {
    setup:
    injectSysConfig("integrations.enabled", "false")
    injectSysConfig("integration.${value}.enabled", "true")
    def target = new TestDefaultInstrumenter(name, altName)

    expect:
    target.enabled == enabled

    where:
    value             | enabled | name          | altName
    "test"            | true    | "test"        | "asdf"
    "duplicate"       | true    | "duplicate"   | "duplicate"
    "bad"             | false   | "not"         | "valid"
    "altTest"         | true    | "asdf"        | "altTest"
    "dash-test"       | true    | "dash-test"   | "asdf"
    "underscore_test" | true    | "asdf"        | "underscore_test"
    "period.test"     | true    | "period.test" | "asdf"
  }

  def "configure env var enabled for #value when default is disabled"() {
    setup:
    injectEnvConfig("DD_INTEGRATIONS_ENABLED", "false")
    injectEnvConfig("DD_INTEGRATION_${value}_ENABLED", "true")

    when:
    def target = new TestDefaultInstrumenter(name, altName)

    then:
    EnvironmentVariables.get("DD_INTEGRATION_${value}_ENABLED") == "true"
    target.enabled == enabled

    where:
    value             | enabled | name          | altName
    "TEST"            | true    | "test"        | "asdf"
    "DUPLICATE"       | true    | "duplicate"   | "duplicate"
    "BAD"             | false   | "not"         | "valid"
    "ALTTEST"         | true    | "asdf"        | "altTest"
    "DASH_TEST"       | true    | "dash-test"   | "asdf"
    "UNDERSCORE_TEST" | true    | "asdf"        | "underscore_test"
    "PERIOD_TEST"     | true    | "period.test" | "asdf"
  }

  class TestDefaultInstrumenter extends InstrumenterModule.Tracing {

    TestDefaultInstrumenter(String instrumentationName) {
      super(instrumentationName)
    }

    TestDefaultInstrumenter(String instrumentationName, String additionalName) {
      super(instrumentationName, [additionalName] as String[])
    }
  }
}
