package datadog.trace.api.env

import datadog.trace.api.config.GeneralConfig
import datadog.trace.util.test.DDSpecification
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties

class CapturedEnvironmentTest extends DDSpecification {

  @Rule
  public final RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties()

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def "non autodetected servicename"() {
    setup:
    System.setProperty("sun.java.command", "")
    environmentVariables.set("JAVA_MAIN_CLASS", null)

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == null
  }

  def "set servicename by env var JAVA_MAIN_CLASS"() {
    setup:
    environmentVariables.set("JAVA_MAIN_CLASS", "org.example.App")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == "org.example.App"
  }

  def "set servicename by sysprop 'sun.java.command' with class"() {
    setup:
    System.setProperty("sun.java.command", "org.example.App arg1 arg2 arg3")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == "org.example.App"
  }

  def "set servicename by sysprop 'sun.java.command' with jar"() {
    setup:
    System.setProperty("sun.java.command", "foo/bar/example.jar arg1 arg2 arg3")

    when:
    def capturedEnv = new CapturedEnvironment()

    then:
    def props = capturedEnv.properties
    props.get(GeneralConfig.SERVICE_NAME) == "example.jar"
  }

}
