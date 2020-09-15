package datadog.trace.core.util

import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.Config
import datadog.trace.util.test.DDSpecification

import java.lang.management.ManagementFactory

class SystemAccessTest extends DDSpecification {
  def "No system provider - profiling enabled"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "true")
    }
    SystemAccess.disableJmx()

    when:
    def threadCpuTime1 = SystemAccess.getCurrentThreadCpuTime()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = SystemAccess.getCurrentThreadCpuTime()

    then:
    sum > 0
    threadCpuTime1 == Long.MIN_VALUE
    threadCpuTime2 == Long.MIN_VALUE

    cleanup:
    SystemAccess.disableJmx()
  }

  def "No system provider - profiling disabled"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "false")
    }
    SystemAccess.enableJmx()

    when:
    def threadCpuTime1 = SystemAccess.getCurrentThreadCpuTime()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = SystemAccess.getCurrentThreadCpuTime()

    then:
    sum > 0
    threadCpuTime1 == Long.MIN_VALUE
    threadCpuTime2 == Long.MIN_VALUE

    cleanup:
    SystemAccess.disableJmx()
  }

  def "JMX system provider"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "true")
    }
    SystemAccess.enableJmx()

    when:
    def threadCpuTime1 = SystemAccess.getCurrentThreadCpuTime()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = SystemAccess.getCurrentThreadCpuTime()

    then:
    sum > 0
    threadCpuTime1 != Long.MIN_VALUE
    threadCpuTime2 != Long.MIN_VALUE
    threadCpuTime2 > threadCpuTime1

    cleanup:
    SystemAccess.disableJmx()
  }

  def "JMX get current process id"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "true")
    }
    SystemAccess.enableJmx()

    when:
    def pid = SystemAccess.getCurrentPid()

    then:
      pid > 0
      pid == Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0])

    cleanup:
    SystemAccess.disableJmx()
  }

  def "No JMX - get current process id"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "true")
    }

    when:
    def pid = SystemAccess.getCurrentPid()

    then:
    pid == 0
  }
}
