package datadog.trace.bootstrap.instrumentation.messaging

import datadog.trace.test.util.DDSpecification

class DatadogAttributeParserTest extends DDSpecification {

  def "normalizes hex trace id and keeps propagation tags"() {
    setup:
    def extracted = [:]
    def json = '{"x-datadog-trace-id":"69c0b39f000000002bd8d3176581bf4f","x-datadog-parent-id":"2585836967003721736","x-datadog-sampling-priority":"1","x-datadog-tags":"_dd.p.dm=-1,_dd.p.tid=69c0b39f00000000"}'

    when:
    DatadogAttributeParser.forEachProperty({ key, value ->
      extracted[key] = value
      true
    }, json)

    then:
    extracted["x-datadog-trace-id"] == "3159507236041113423"
    extracted["x-datadog-parent-id"] == "2585836967003721736"
    extracted["x-datadog-sampling-priority"] == "1"
    extracted["x-datadog-tags"] == "_dd.p.dm=-1,_dd.p.tid=69c0b39f00000000"
  }
}
