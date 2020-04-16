package datadog.trace.common.util

import spock.lang.Specification

class ThreadCpuTimeAccessTest extends Specification {
  def "No thread CPU time provider"() {
    setup:
    ThreadCpuTimeAccess.disableJmx()

    when:
    def threadCpuTime1 = ThreadCpuTimeAccess.getCurrentThreadCpuTime()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = ThreadCpuTimeAccess.getCurrentThreadCpuTime()

    then:
    sum > 0
    threadCpuTime1 == Long.MIN_VALUE
    threadCpuTime2 == Long.MIN_VALUE
  }

  def "JMX thread CPU time provider"() {
    setup:
    ThreadCpuTimeAccess.enableJmx()

    when:
    def threadCpuTime1 = ThreadCpuTimeAccess.getCurrentThreadCpuTime()
    // burn some cpu
    def sum = 0
    for (int i = 0; i < 10_000; i++) {
      sum += i
    }
    def threadCpuTime2 = ThreadCpuTimeAccess.getCurrentThreadCpuTime()

    then:
    sum > 0
    threadCpuTime1 != Long.MIN_VALUE
    threadCpuTime2 != Long.MIN_VALUE
    threadCpuTime2 > threadCpuTime1
  }
}
