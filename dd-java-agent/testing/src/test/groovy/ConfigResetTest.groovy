import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import spock.lang.Shared

class ConfigResetTest extends AgentTestRunner {

  @Shared
  def sharedInstance = new ShouldBeConstructedWithoutConfig()

  @Shared
  def sharedInstanceCreatedInSetup

  def notSharedInstance = new ShouldBeConstructedWithoutConfig()

  def notSharedInstanceCreatedInSetup

  def setupSpec() {
    sharedInstanceCreatedInSetup = new ShouldBeConstructedWithoutConfig()
  }

  def setup() {
    notSharedInstanceCreatedInSetup = new ShouldBeConstructedWithoutConfig()
  }

  static void checkStaticAssertions() {
    assert System.getProperty("dd.trace.enabled") == null
    assert System.getenv("DD_TRACE_ENABLED") == null
    assert Config.get().isTraceEnabled()
  }

  void checkAssertions() {
    checkStaticAssertions()

    // These assertions are to satisfy codeNarc
    assert sharedInstance != null
    assert sharedInstanceCreatedInSetup != null
    assert notSharedInstance != null
    assert notSharedInstanceCreatedInSetup != null
    assert sharedInstanceCreatedInSetup != null
    assert notSharedInstanceCreatedInSetup != null
  }

  void injectAllConfigs() {
    injectSysConfig("dd.trace.enabled", "false")
    injectEnvConfig("DD_TRACE_ENABLED", "false")
  }

  def "first test"() {
    setup:
    checkAssertions()
    def preInstance = new ShouldBeConstructedWithoutConfig()

    injectAllConfigs()

    when:
    injectAllConfigs()

    then:
    assert preInstance != null
    injectAllConfigs()
    noExceptionThrown()

    cleanup:
    injectAllConfigs()
  }

  def "second test"() {
    setup:
    checkAssertions()
    def preInstance = new ShouldBeConstructedWithoutConfig()

    injectAllConfigs()

    when:
    injectAllConfigs()

    then:
    assert preInstance != null
    injectAllConfigs()
    noExceptionThrown()

    cleanup:
    injectAllConfigs()
  }
}

class ShouldBeConstructedWithoutConfig {
  ShouldBeConstructedWithoutConfig() {
    ConfigResetTest.checkStaticAssertions()
  }
}
