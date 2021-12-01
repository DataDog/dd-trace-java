package datadog.trace.api.profiling

import datadog.trace.test.util.DDSpecification

class ProfilingListenersRegistryTest extends DDSpecification {
  static class TypeA implements ObservableType {}

  static class TypeB implements ObservableType {}

  def "Test host registry"() {
    when:
    def hostA = ProfilingListenersRegistry.getHost(ProfilingListenersRegistryTest.TypeA)
    def hostA1 = ProfilingListenersRegistry.getHost(ProfilingListenersRegistryTest.TypeA)
    def hostB = ProfilingListenersRegistry.getHost(ProfilingListenersRegistryTest.TypeB)

    then:
    hostA != null
    hostA1 != null
    hostB != null
    hostA == hostA1
    hostA != hostB
  }
}
