package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

class IntegrationsCollectorTest extends DDSpecification {

  def "update-drain integrations"() {
    setup:
    IntegrationsCollector.get().integrations.offer(
      new IntegrationsCollector.Integration(
      names: ['spring'],
      enabled: true
      )
      )
    IntegrationsCollector.get().integrations.offer(
      new IntegrationsCollector.Integration(
      names: ['netty', 'jdbc'],
      enabled: false
      )
      )

    when:
    IntegrationsCollector.get().update(['netty', 'jetty'], true)

    then:
    IntegrationsCollector.get().drain() == ['spring': true, 'netty': true, 'jdbc': false, 'jetty': true]
    IntegrationsCollector.get().drain() == [:]
  }
}
