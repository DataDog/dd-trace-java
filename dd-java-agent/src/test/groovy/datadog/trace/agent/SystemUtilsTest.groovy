package datadog.trace.agent

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import spock.lang.Timeout

import jvmbootstraptest.SystemUtilsCheck

import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME

@Timeout(30)
class SystemUtilsTest extends DDSpecification {

  def "config initializes by setting props and confirming sys props override env vars"() {
    setup:
    def prop = new Properties()
    prop.setProperty(SERVICE_NAME, "something else")
    environmentVariables.set(DD_SERVICE_NAME_ENV, "still something else")
    System.setProperty("dd." + SERVICE_NAME, "what we want")

    when:
    Config config = Config.get(prop)
    // def config = new Config()

    then:
    config.serviceName == "what we want"
    SystemUtilsCheck.runTestJvm(null) == 0
  }

  def "no env access"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "still something else")

    when:
    //    def config = new Config()

    then:
    System.getenv(DD_SERVICE_NAME_ENV) == null
    SystemUtilsCheck.runTestJvm(SystemUtilsCheck.BlockEnvVar) == 0
  }

  def "minimal property access"() {
    setup:
    System.setProperty("dd." + SERVICE_NAME, "what we want")

    when:
    //    def config = new Config()

    then:
    System.getProperty("dd." + SERVICE_NAME) == null
    SystemUtilsCheck.runTestJvm(SystemUtilsCheck.BlockPropertyVar, true) == 0
  }
}
