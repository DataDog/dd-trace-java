package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.LogManagerSetter
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class CustomLogManagerTest extends Specification {

  private static final String DEFAULT_LOG_LEVEL = "debug"

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
      ]
      , []
      , [:]
      , true) == 0
  }

  def "agent services startup is delayed even if configured log manager on system classpath"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(LogManagerSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Djava.util.logging.manager=jvmbootstraptest.CustomLogManager"
      ]
      , []
      , [:]
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
      ]
      , []
      , [:]
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
      ]
      , []
      , [:]
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
      ]
      , []
      , ["JBOSS_HOME": "/"]
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
      ]
      , []
      , ["JBOSS_HOME": "/"]
      , true) == 0
  }
}
