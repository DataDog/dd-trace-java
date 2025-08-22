package datadog.trace.common.metrics

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl

class MetricsAggregatorFactoryTest extends DDSpecification {

  def "when metrics disabled no-op aggregator created"() {
    setup:
    Config config = Mock(Config)
    config.isTracerMetricsEnabled() >> false
    def sco = Mock(SharedCommunicationObjects)
    sco.agentUrl = HttpUrl.parse("http://localhost:8126")
    expect:
    def aggregator = MetricsAggregatorFactory.createMetricsAggregator(config, sco, HealthMetrics.NO_OP,)
    assert aggregator instanceof NoOpMetricsAggregator
  }

  def "when metrics enabled conflating aggregator created"() {
    setup:
    Config config = Spy(Config.get())
    config.isTracerMetricsEnabled() >> true
    def sco = Mock(SharedCommunicationObjects)
    sco.agentUrl = HttpUrl.parse("http://localhost:8126")
    expect:
    def aggregator = MetricsAggregatorFactory.createMetricsAggregator(config, sco, HealthMetrics.NO_OP,
      )
    assert aggregator instanceof ConflatingMetricsAggregator
  }
}
