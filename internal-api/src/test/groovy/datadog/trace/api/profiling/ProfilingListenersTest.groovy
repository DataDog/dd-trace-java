package datadog.trace.api.profiling

import datadog.trace.test.util.DDSpecification

class ProfilingListenersTest extends DDSpecification {
  def "Verify interactions"() {
    setup:
    def instance = new ProfilingListeners<>()
    def listener = Mock(ProfilingListener)
    def snapshot = Mock(ProfilingSnapshot)
    when:
    instance.addListener(listener)
    instance.fireOnData(snapshot)
    then:
    1 * listener.onData(snapshot)

    when:
    instance.removeListener(listener)
    instance.fireOnData(snapshot)
    then:
    0 * listener.onData(snapshot)
  }
}
