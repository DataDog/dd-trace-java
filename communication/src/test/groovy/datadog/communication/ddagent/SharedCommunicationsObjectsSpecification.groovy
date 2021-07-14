package datadog.communication.ddagent

import datadog.communication.monitor.Monitoring
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class SharedCommunicationsObjectsSpecification extends DDSpecification {
  SharedCommunicationObjects sco = new SharedCommunicationObjects()

  void 'createRemaining with nothing populated'() {
    Config config = Mock()

    when:
    sco.createRemaining(config)

    then:
    1 * config.agentUrl >> 'http://example.com/'
    1 * config.agentUnixDomainSocket >> null
    1 * config.traceAgentV05Enabled >> false
    1 * config.tracerMetricsEnabled >> false

    sco.agentUrl as String == 'http://example.com/'
    sco.okHttpClient != null
    sco.monitoring.is(Monitoring.DISABLED)
    sco.featuresDiscovery != null
  }

  void 'createRemaining with everything populated'() {
    Config config = Mock()

    def url = HttpUrl.get("http://example.com")
    OkHttpClient okHttpClient = Mock()
    Monitoring monitoring = Mock()
    DDAgentFeaturesDiscovery agentFeaturesDiscovery = Mock()

    sco.agentUrl = url
    sco.okHttpClient = okHttpClient
    sco.monitoring = monitoring
    sco.featuresDiscovery = agentFeaturesDiscovery

    when:
    sco.createRemaining(config)

    then:
    0 * _
    sco.agentUrl.is(url)
    sco.okHttpClient.is(okHttpClient)
    sco.monitoring.is(monitoring)
    sco.featuresDiscovery.is(agentFeaturesDiscovery)
  }
}
