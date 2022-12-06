package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class TelemetryCollectorsTest extends DDSpecification {

  def "update-drain integrations"() {
    setup:
    IntegrationsCollector.integrations.offer(
      new IntegrationsCollector.Integration(
      names: ['spring'],
      enabled: true
      )
      )
    IntegrationsCollector.integrations.offer(
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

  def "put-get configurations"() {
    setup:
    ConfigCollector.get().clear()

    when:
    ConfigCollector.get().put('key1', 'value1')
    ConfigCollector.get().put('key2', 'value2')
    ConfigCollector.get().put('key1', 'replaced')

    then:
    ConfigCollector.get() == [key1: 'replaced', key2: 'value2']
  }

  def "hide pii configuration data"() {
    setup:
    ConfigCollector.get().clear()

    when:
    ConfigCollector.get().put('DD_API_KEY', 'sensitive data')

    then:
    ConfigCollector.get().get('DD_API_KEY') == '<hidden>'
  }
}
