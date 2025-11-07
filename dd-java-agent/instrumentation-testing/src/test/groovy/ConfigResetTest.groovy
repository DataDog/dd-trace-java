import datadog.environment.EnvironmentVariables
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Config
import spock.lang.Shared

class ConfigResetTest extends InstrumentationSpecification {

  @Shared
  def sharedInstance = checkStaticAssertions()

  def notSharedInstance = checkStaticAssertions()

  def setupSpec() {
    checkStaticAssertions()
  }

  def setup() {
    checkStaticAssertions()
  }

  static Object checkStaticAssertions() {
    assert System.getProperty("dd.trace.enabled") == null
    assert EnvironmentVariables.get("DD_TRACE_ENABLED") == null
    assert Config.get().isTraceEnabled()

    // Returning a new object so this can be used in field initializations
    return new Object()
  }

  void checkAssertions() {
    checkStaticAssertions()

    // These assertions are to satisfy codeNarc
    assert sharedInstance != null
    assert notSharedInstance != null
  }

  void injectAllConfigs() {
    injectSysConfig("dd.trace.enabled", "false")
    injectEnvConfig("DD_TRACE_ENABLED", "false")
  }

  def "first test has clean config"() {
    setup:
    checkAssertions()

    injectAllConfigs()

    when:
    injectAllConfigs()

    then:
    injectAllConfigs()
    noExceptionThrown()

    cleanup:
    injectAllConfigs()
  }

  def "second test has clean config"() {
    setup:
    checkAssertions()

    injectAllConfigs()

    when:
    injectAllConfigs()

    then:
    injectAllConfigs()
    noExceptionThrown()

    cleanup:
    injectAllConfigs()
  }

  def "injecting system config"() {
    setup:
    checkAssertions()

    when:
    injectSysConfig("dd.trace.enabled", "true")

    then:
    System.getProperty("dd.trace.enabled") == "true"
    Config.get().isTraceEnabled()

    when:
    injectSysConfig("dd.trace.enabled", "false")

    then:
    System.getProperty("dd.trace.enabled") == "false"
    !Config.get().isTraceEnabled()
  }

  def "injecting system config without prefix"() {
    setup:
    checkAssertions()

    when:
    injectSysConfig("trace.enabled", "true")

    then:
    System.getProperty("dd.trace.enabled") == "true"
    Config.get().isTraceEnabled()

    when:
    injectSysConfig("trace.enabled", "false")

    then:
    System.getProperty("dd.trace.enabled") == "false"
    !Config.get().isTraceEnabled()
  }

  def "removing sys config"() {
    setup:
    checkAssertions()

    when:
    injectSysConfig("dd.trace.enabled", "false")

    then:
    System.getProperty("dd.trace.enabled") == "false"
    !Config.get().isTraceEnabled()

    when:
    removeSysConfig("dd.trace.enabled")

    then:
    System.getProperty("dd.trace.enabled") == null
    Config.get().isTraceEnabled()

    when:
    injectSysConfig("dd.trace.enabled", "false")

    then:
    System.getProperty("dd.trace.enabled") == "false"
    !Config.get().isTraceEnabled()

    when:
    removeSysConfig("trace.enabled")

    then:
    System.getProperty("dd.trace.enabled") == null
    Config.get().isTraceEnabled()
  }

  def "injecting env config"() {
    setup:
    checkAssertions()

    when:
    injectEnvConfig("DD_TRACE_ENABLED", "true")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == "true"
    Config.get().isTraceEnabled()

    when:
    injectEnvConfig("DD_TRACE_ENABLED", "false")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == "false"
    !Config.get().isTraceEnabled()
  }

  def "injecting env config without prefix"() {
    setup:
    checkAssertions()

    when:
    injectEnvConfig("TRACE_ENABLED", "true")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == "true"
    Config.get().isTraceEnabled()

    when:
    injectEnvConfig("TRACE_ENABLED", "false")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == "false"
    !Config.get().isTraceEnabled()
  }

  def "removing env config"() {
    setup:
    checkAssertions()

    when:
    injectEnvConfig("DD_TRACE_ENABLED", "false")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == "false"
    !Config.get().isTraceEnabled()

    when:
    removeEnvConfig("DD_TRACE_ENABLED")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == null
    Config.get().isTraceEnabled()

    when:
    injectEnvConfig("DD_TRACE_ENABLED", "false")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == "false"
    !Config.get().isTraceEnabled()

    when:
    removeEnvConfig("TRACE_ENABLED")

    then:
    EnvironmentVariables.get("DD_TRACE_ENABLED") == null
    Config.get().isTraceEnabled()
  }
}

