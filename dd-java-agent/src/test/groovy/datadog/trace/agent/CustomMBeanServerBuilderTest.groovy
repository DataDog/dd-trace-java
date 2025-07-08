package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.MBeanServerBuilderSetter
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class CustomMBeanServerBuilderTest extends Specification {

  private static final String DEFAULT_LOG_LEVEL = "debug"

  // Run all tests using forked jvm so we try different JMX settings
  def "JMXFetch starts up in premain with no custom MBeanServerBuilder set"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddd.instrumentation.telemetry.enabled=false",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL"
      ]
      , []
      , [:]
      , true) == 0
  }

  def "JMXFetch startup is delayed even if configured MBeanServerBuilder on system classpath"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddd.instrumentation.telemetry.enabled=false",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Djavax.management.builder.initial=jvmbootstraptest.CustomMBeanServerBuilder"
      ]
      , []
      , [:]
      , true) == 0
  }

  def "JMXFetch startup is delayed with javax.management.builder.initial sysprop"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddd.instrumentation.telemetry.enabled=false",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Djavax.management.builder.initial=jvmbootstraptest.MissingMBeanServerBuilder"
      ]
      , []
      , [:]
      , true) == 0
  }

  def "JMXFetch startup is delayed with tracer custom JMX builder setting"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddd.instrumentation.telemetry.enabled=false",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Ddd.app.customjmxbuilder=true"
      ]
      , []
      , [:]
      , true) == 0
  }

  def "JMXFetch starts up in premain forced by customjmxbuilder=false"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.refresh-beans-period=1",
        "-Ddd.profiling.enabled=true",
        "-Ddd.instrumentation.telemetry.enabled=false",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL",
        "-Ddd.app.customjmxbuilder=false",
        "-Djavax.management.builder.initial=jvmbootstraptest.CustomMBeanServerBuilder"
      ]
      , []
      , [:]
      , true) == 0
  }
}
