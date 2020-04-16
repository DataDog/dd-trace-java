package datadog.opentracing.util

import datadog.trace.common.util.ThreadCpuTime
import spock.lang.Specification

import java.lang.management.ManagementFactory

class ThreadCpuTimeTest extends Specification {
  def "No thread CPU time provider"() {
    setup:
    ThreadCpuTime.initialize(null)

    when:
    def threadCpuTime1 = ThreadCpuTime.get()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = ThreadCpuTime.get()

    then:
    sum > 0
    threadCpuTime1 == Long.MIN_VALUE
    threadCpuTime2 == Long.MIN_VALUE
  }

  def "JMX thread CPU time provider"() {
    setup:
    ThreadCpuTime.initialize(ManagementFactory.getThreadMXBean().&getCurrentThreadCpuTime)

    when:
    def threadCpuTime1 = ThreadCpuTime.get()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = ThreadCpuTime.get()

    then:
    sum > 0
    threadCpuTime1 != Long.MIN_VALUE
    threadCpuTime2 != Long.MIN_VALUE
    threadCpuTime2 > threadCpuTime1
  }
}
