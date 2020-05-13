package datadog.trace.core.util


import datadog.trace.util.test.DDSpecification

class ThreadStackAccessTest extends DDSpecification {
  def "No ThreadStack provider"() {
    setup:
    ThreadStackAccess.disableJmx()

    when:
    def provider = ThreadStackAccess.currentThreadStackProvider

    then:
    provider == NoneThreadStackProvider.INSTANCE

    cleanup:
    ThreadStackAccess.disableJmx()
  }

  def "JMX ThreadStack provider"() {
    setup:
    ThreadStackAccess.enableJmx()

    when:
    def provider = ThreadStackAccess.getCurrentThreadStackProvider()
    def stackTraces = new ArrayList()
    provider.getStackTrace(new HashSet<Long>(Arrays.asList(Thread.currentThread().getId())), stackTraces)

    then:
    provider != NoneThreadStackProvider.INSTANCE
    !stackTraces.isEmpty()

    cleanup:
    ThreadStackAccess.disableJmx()
  }
}
