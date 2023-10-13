package datadog.trace.common.metrics

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class MetricsAggregatorFactoryTest extends DDSpecification {

  def "when metrics disabled no-op aggregator created"() {
    setup:
    Config config = Mock(Config)
    config.isTracerMetricsEnabled() >> false
    expect:
    def aggregator = MetricsAggregatorFactory.createMetricsAggregator(config, Mock(SharedCommunicationObjects))
    assert aggregator instanceof NoOpMetricsAggregator
  }

  def "when metrics enabled conflating aggregator created"() {
    setup:
    Config config = Spy(Config.get())
    config.isTracerMetricsEnabled() >> true
    expect:
    def aggregator = MetricsAggregatorFactory.createMetricsAggregator(config, Mock(SharedCommunicationObjects))
    assert aggregator instanceof ConflatingMetricsAggregator
  }
}
