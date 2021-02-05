package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

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
}
