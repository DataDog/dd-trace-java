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

    cleanup:
    ThreadStackAccess.disableJmx()
  }

  def "JMX ThreadStack provider"() {
    setup:
    ThreadStackAccess.enableJmx()

    when:
    def provider = ThreadStackAccess.getCurrentThreadStackProvider()
    def stackTraces = provider.getStackTrace(Collections.singletonList(Thread.currentThread().getId()))
    def threadInfos = provider.getThreadInfo(Collections.singletonList(Thread.currentThread().getId()))

    then:
    provider instanceof JmxThreadStackProvider
    !stackTraces.isEmpty()
    !threadInfos.isEmpty()

    cleanup:
    ThreadStackAccess.disableJmx()
  }
}
