package datadog.trace.core.util


import datadog.trace.util.test.DDSpecification

class ThreadStackAccessTest extends DDSpecification {
  def "No ThreadStack provider"() {
    setup:
    ThreadStackAccess.disableJmx()

    when:
    def provider = ThreadStackAccess.currentThreadStackProvider

    then:
    provider instanceof NoneThreadStackProvider
    provider.getStackTrace(new long[0]).length == 0
    provider.getThreadInfo(new long[0]).length == 0

    cleanup:
    ThreadStackAccess.disableJmx()
  }

  def "JMX ThreadStack provider"() {
    setup:
    ThreadStackAccess.enableJmx()

    when:
    def provider = ThreadStackAccess.getCurrentThreadStackProvider()
    def stackTraces = provider.getStackTrace([Thread.currentThread().getId()] as long[])
    def threadInfos = provider.getThreadInfo([Thread.currentThread().getId()] as long[])

    then:
    provider instanceof JmxThreadStackProvider
    stackTraces.length > 0
    threadInfos.length > 0

    cleanup:
    ThreadStackAccess.disableJmx()
  }
}
