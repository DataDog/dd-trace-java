package datadog.trace.api.profiling

import datadog.trace.test.util.DDSpecification

class ProfilingListenerHostsTest extends DDSpecification {
  static class TypeA implements ObservableType {}

  static class TypeB implements ObservableType {}

  def "Test host registry"() {
    when:
    def hostA = ProfilingListenerHosts.getHost(ProfilingListenerHostsTest.TypeA)
    def hostA1 = ProfilingListenerHosts.getHost(ProfilingListenerHostsTest.TypeA)
    def hostB = ProfilingListenerHosts.getHost(ProfilingListenerHostsTest.TypeB)

    then:
    hostA != null
    hostA1 != null
    hostB != null
    hostA == hostA1
    hostA != hostB
  }
}
