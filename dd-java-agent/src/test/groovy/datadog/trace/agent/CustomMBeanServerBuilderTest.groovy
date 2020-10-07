package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.MBeanServerBuilderSetter
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class CustomMBeanServerBuilderTest extends Specification {

  private static final String DEFAULT_LOG_LEVEL = "debug"
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF"

  // Run all tests using forked jvm so we try different JMX settings
  def "JMXFetch starts up in premain with no custom MBeanServerBuilder set"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , ["-Ddd.jmxfetch.enabled=true", "-Ddd.jmxfetch.refresh-beans-period=1", "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL"] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "JMXFetch starts up in premain if configured MBeanServerBuilder on system classpath"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , ["-Ddd.jmxfetch.enabled=true", "-Ddd.jmxfetch.refresh-beans-period=1", "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL", "-Djavax.management.builder.initial=jvmbootstraptest.CustomMBeanServerBuilder"] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "JMXFetch startup is delayed with javax.management.builder.initial sysprop"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , ["-Ddd.jmxfetch.enabled=true", "-Ddd.jmxfetch.refresh-beans-period=1", "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL", "-Djavax.management.builder.initial=jvmbootstraptest.MissingMBeanServerBuilder"] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "JMXFetch startup is delayed with tracer custom JMX builder setting"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , ["-Ddd.jmxfetch.enabled=true", "-Ddd.jmxfetch.refresh-beans-period=1", "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL", "-Ddd.app.customjmxbuilder=true"] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }

  def "JMXFetch starts up in premain forced by customjmxbuilder=false"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(MBeanServerBuilderSetter.getName()
      , ["-Ddd.jmxfetch.enabled=true", "-Ddd.jmxfetch.refresh-beans-period=1", "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL", "-Ddd.app.customjmxbuilder=false", "-Djavax.management.builder.initial=jvmbootstraptest.CustomMBeanServerBuilder"] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , true) == 0
  }
}
