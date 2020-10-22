package datadog.trace.api.env

import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification

class CapturedEnvironmentTest extends DDSpecification {
  def "non autodetected service.name with null command"() {
    setup:
    System.clearProperty("sun.java.command")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == null
  }

  def "non autodetected service.name with empty command"() {
    setup:
    System.setProperty("sun.java.command", "")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == null
  }

  def "non autodetected service.name with all blanks command"() {
    setup:
    System.setProperty("sun.java.command", " ")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == null
  }

  def "set service.name by sysprop 'sun.java.command' with class"() {
    setup:
    System.setProperty("sun.java.command", "org.example.App -Dfoo=bar arg2 arg3")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == "org.example.App"
  }

  def "set service.name by sysprop 'sun.java.command' with jar"() {
    setup:
    System.setProperty("sun.java.command", "foo/bar/example.jar -Dfoo=bar arg2 arg3")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == "example"
  }

  def "set service.name with real 'sun.java.command' property"() {
    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) != null
  }

}
