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
    def stackTraces = provider.getStackTrace(new HashSet<Long>(Arrays.asList(Thread.currentThread().getId())))

    then:
    provider instanceof JmxThreadStackProvider
    !stackTraces.isEmpty()

    cleanup:
    ThreadStackAccess.disableJmx()
  }
}
