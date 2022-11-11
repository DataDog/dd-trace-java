package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.LogManagerSetter
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class CustomLogManagerTest extends Specification {

  private static final String DEFAULT_LOG_LEVEL = "debug"
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF"

  // Run all tests using forked jvm because groovy has already set the global log manager
  def "agent services starts up in premain with no custom log manager set"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL"
      ] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "agent services starts up in premain if configured log manager on system classpath"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Djava.util.logging.manager=jvmbootstraptest.CustomLogManager"
      ] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "agent services startup is delayed with java.util.logging.manager sysprop"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Djava.util.logging.manager=jvmbootstraptest.MissingLogManager"
      ] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "agent services startup delayed with tracer custom log manager setting"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Ddd.app.customlogmanager=true"
      ] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "agent services startup delayed with JBOSS_HOME environment variable"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Ddd.app.customjmxbuilder=false"
      ] as String[]
      , "" as String[]
      , ["JBOSS_HOME": "/", "DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "agent services startup in premain forced by customlogmanager=false"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Ddd.app.customlogmanager=false",
        "-Ddd.app.customjmxbuilder=false",
        "-Djava.util.logging.manager=jvmbootstraptest.CustomLogManager"
      ] as String[]
      , "" as String[]
      , ["JBOSS_HOME": "/", "DD_API_KEY": API_KEY]
      , true) == 0
  }
}
