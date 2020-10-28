package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification
import org.junit.Assume

import java.lang.management.ManagementFactory

import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED

class SystemAccessTest extends DDSpecification {
  def cleanup() {
    SystemAccess.disableJmx()
  }

  def "Test cpu time"() {
    setup:
    injectSysConfig(PROFILING_ENABLED, profilingEnabled.toString())
    injectSysConfig(HEALTH_METRICS_ENABLED, healthMetricsEnabled.toString())

    if (providerEnabled) {
      SystemAccess.enableJmx()
    } else {
      SystemAccess.disableJmx()
    }

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

    if (hasCpuTime) {
      assert threadCpuTime1 != Long.MIN_VALUE
      assert threadCpuTime2 != Long.MIN_VALUE
      assert threadCpuTime2 > threadCpuTime1
    } else {
      assert threadCpuTime1 == Long.MIN_VALUE
      assert threadCpuTime2 == Long.MIN_VALUE
    }

    where:
    providerEnabled | profilingEnabled | healthMetricsEnabled | hasCpuTime
    false           | false            | false                | false
    false           | false            | true                 | false
    false           | true             | false                | false
    false           | true             | true                 | false
    true            | false            | false                | false
    true            | false            | true                 | true
    true            | true             | false                | true
    true            | true             | true                 | true
  }

  def "Test get current process id"() {
    setup:
    injectSysConfig(PROFILING_ENABLED, profilingEnabled.toString())
    injectSysConfig(HEALTH_METRICS_ENABLED, healthMetricsEnabled.toString())
    if (providerEnabled) {
      SystemAccess.enableJmx()
    } else {
      SystemAccess.disableJmx()
    }

    when:
    def pid = SystemAccess.getCurrentPid()

    then:
    if (hasProcessId) {
      assert pid > 0
      assert pid == Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0])
    } else {
      assert pid == 0
    }

    where:
    providerEnabled | profilingEnabled | healthMetricsEnabled | hasProcessId
    false           | false            | false                | false
    false           | false            | true                 | false
    false           | true             | false                | false
    false           | true             | true                 | false
    true            | false            | false                | false
    true            | false            | true                 | true
    true            | true             | false                | true
    true            | true             | true                 | true
  }

  def "Test getVMArguments"() {
    setup:
    injectSysConfig(PROFILING_ENABLED, profilingEnabled.toString())
    injectSysConfig(HEALTH_METRICS_ENABLED, healthMetricsEnabled.toString())
    if (providerEnabled) {
      SystemAccess.enableJmx()
    } else {
      SystemAccess.disableJmx()
    }

    when:
    def vmArgs = SystemAccess.getVMArguments()

    then:
    assert vmArgs != null

    if (hasVMArgs) {
      assert !vmArgs.isEmpty()
    } else {
      assert vmArgs != null
      assert vmArgs.isEmpty()
    }

    where:
    providerEnabled | profilingEnabled | healthMetricsEnabled | hasVMArgs
    false           | false            | false                | false
    false           | false            | true                 | false
    false           | true             | false                | false
    false           | true             | true                 | false
    true            | false            | false                | false
    true            | false            | true                 | true
    true            | true             | false                | true
    true            | true             | true                 | true
  }

  def "Test executeDiagnosticCommand"() {
    setup:
    def vmVersion = System.getProperty("java.specification.version")
    def vmVendor = System.getProperty("java.vendor")
    Assume.assumeFalse(vmVersion == "1.7")
    Assume.assumeFalse(vmVersion == "1.8" && vmVendor.contains("IBM"))

    injectSysConfig(PROFILING_ENABLED, profilingEnabled.toString())
    injectSysConfig(HEALTH_METRICS_ENABLED, healthMetricsEnabled.toString())
    if (providerEnabled) {
      SystemAccess.enableJmx()
    } else {
      SystemAccess.disableJmx()
    }

    when:
    def result = SystemAccess.executeDiagnosticCommand(
      "jfrConfigure",
      [["stackdepth=128"].toArray() as String[]].toArray() as Object[],
      [String[].class.getName()].toArray() as String[])

    then:
    noExceptionThrown()

    if (commandExecutes) {
      assert "Stack depth: 128" == result
    } else {
      assert "Not executed, JMX not initialized." == result
    }

    where:
    providerEnabled | profilingEnabled | healthMetricsEnabled | commandExecutes
    false           | false            | false                | false
    false           | false            | true                 | false
    false           | true             | false                | false
    false           | true             | true                 | false
    true            | false            | false                | false
    true            | false            | true                 | true
    true            | true             | false                | true
    true            | true             | true                 | true
  }
}
