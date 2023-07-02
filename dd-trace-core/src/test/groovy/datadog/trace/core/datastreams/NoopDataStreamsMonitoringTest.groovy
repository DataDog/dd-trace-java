package datadog.trace.core.datastreams

import datadog.trace.core.propagation.HttpCodec
import datadog.trace.core.test.DDCoreSpecification

class NoopDataStreamsMonitoringTest extends DDCoreSpecification {
  def "Ensure decorator do not modify extractor"() {
    when:
    def checkpointer = new NoopDataStreamsMonitoring()
    def extractor = Mock(HttpCodec.Extractor)

    then:
    checkpointer.decorate(extractor) == extractor
  }
}
