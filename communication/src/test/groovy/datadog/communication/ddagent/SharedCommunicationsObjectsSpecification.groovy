package datadog.communication.ddagent

import static datadog.trace.api.ProtocolVersion.V0_4
import static datadog.trace.api.config.TracerConfig.AGENT_HOST

import datadog.metrics.api.Monitoring
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class SharedCommunicationsObjectsSpecification extends DDSpecification {
  SharedCommunicationObjects sco = new SharedCommunicationObjects()

  void 'nothing populated'() {
    given:
    Config config = Mock()

    when:
    sco.createRemaining(config)

    then:
    1 * config.agentUrl >> 'http://example.com/'
    1 * config.agentNamedPipe >> null
    1 * config.agentTimeout >> 1
    1 * config.agentUnixDomainSocket >> null
    sco.agentUrl as String == 'http://example.com/'
    sco.agentHttpClient != null
    sco.monitoring.is(Monitoring.DISABLED)

    when:
    sco.featuresDiscovery(config)

    then:
    1 * config.protocolVersion >> V0_4
    1 * config.tracerMetricsEnabled >> false
    sco.featuresDiscovery != null

    when:
    sco.configurationPoller(config)

    then:
    1 * config.remoteConfigEnabled >> false
    sco.configurationPoller == null

    when:
    sco.configurationPoller(config)

    then:
    1 * config.remoteConfigEnabled >> true
    1 * config.finalRemoteConfigUrl >> 'http://localhost:8080/config'
    1 * config.remoteConfigTargetsKeyId >> Config.get().remoteConfigTargetsKeyId
    1 * config.remoteConfigTargetsKey >> Config.get().remoteConfigTargetsKey
    sco.configurationPoller != null
  }

  void 'populates ConfigurationPoller even without config endpoint'() {
    given:
    Config config = Mock()

    when:
    sco.configurationPoller(config)

    then:
    1 * config.agentUrl >> 'http://example.com/'
    1 * config.remoteConfigEnabled >> true
    1 * config.finalRemoteConfigUrl >> null
    1 * config.remoteConfigTargetsKeyId >> Config.get().remoteConfigTargetsKeyId
    1 * config.remoteConfigTargetsKey >> Config.get().remoteConfigTargetsKey
    sco.configurationPoller != null
  }

  void 'createRemaining with everything populated'() {
    Config config = Mock()

    def url = HttpUrl.get("http://example.com")
    OkHttpClient okHttpClient = Mock()
    Monitoring monitoring = Mock()
    DDAgentFeaturesDiscovery agentFeaturesDiscovery = Mock()

    sco.agentUrl = url
    sco.agentHttpClient = okHttpClient
    sco.monitoring = monitoring
    sco.featuresDiscovery = agentFeaturesDiscovery

    when:
    sco.createRemaining(config)

    then:
    1 * config.isCiVisibilityEnabled()
    1 * config.getAgentTimeout()
    1 * config.isForceClearTextHttpForIntakeClient()
    1 * config.getHttpsProxy()
    1 * config.getNoProxyHosts()
    0 * _
    sco.agentUrl.is(url)
    sco.agentHttpClient.is(okHttpClient)
    sco.monitoring.is(monitoring)
    sco.featuresDiscovery.is(agentFeaturesDiscovery)
  }

  void 'supports ipv6 agent host w/o brackets'() {
    given:
    injectSysConfig(AGENT_HOST, "2600:1f18:19c0:bd07:d55b::17")
    Config config = Mock()

    when:
    sco.createRemaining(config)

    then:
    1 * config.getAgentUrl() >> 'http://[2600:1f18:19c0:bd07:d55b::17]:8126'
    1 * config.agentNamedPipe >> null
    1 * config.agentTimeout >> 1
    1 * config.agentUnixDomainSocket >> null
    sco.agentUrl as String == 'http://[2600:1f18:19c0:bd07:d55b::17]:8126/'
  }

  void 'supports ipv6 agent host w/ brackets'() {
    given:
    injectSysConfig(AGENT_HOST, "[2600:1f18:19c0:bd07:d55b::17]")
    Config config = Mock()

    when:
    sco.createRemaining(config)

    then:
    1 * config.getAgentUrl() >> 'http://[2600:1f18:19c0:bd07:d55b::17]:8126'
    1 * config.agentNamedPipe >> null
    1 * config.agentTimeout >> 1
    1 * config.agentUnixDomainSocket >> null
    sco.agentUrl as String == 'http://[2600:1f18:19c0:bd07:d55b::17]:8126/'
  }

  void 'creates intake http client'() {
    when:
    def client = sco.getIntakeHttpClient()

    then:
    client != null
  }

  void 'configures standard HTTPS proxy for intake while preserving no-proxy hosts'() {
    given:
    Config config = Mock()
    sco.agentUrl = HttpUrl.get("http://example.com")
    sco.agentHttpClient = Mock(OkHttpClient)
    sco.monitoring = Monitoring.DISABLED
    sco.featuresDiscovery = Mock(DDAgentFeaturesDiscovery)

    when:
    sco.createRemaining(config)
    def selector = sco.getIntakeHttpClient().proxySelector()

    then:
    1 * config.isCiVisibilityEnabled() >> false
    1 * config.getAgentTimeout() >> 1
    1 * config.isForceClearTextHttpForIntakeClient() >> false
    1 * config.getHttpsProxy() >> "http://proxy.example:8181"
    1 * config.getNoProxyHosts() >> (["direct.example", ".internal.example"] as Set)
    0 * _

    and:
    def selected = selector.select(new URI("https://event-platform-intake.datadoghq.com"))
    selected.size() == 1
    selected[0].type() == Proxy.Type.HTTP
    selected[0].address() == new InetSocketAddress("proxy.example", 8181)
    selector.select(new URI("https://direct.example")) == [Proxy.NO_PROXY]
    selector.select(new URI("https://service.internal.example")) == [Proxy.NO_PROXY]
  }

  void 'parses supported HTTPS proxy forms without logging credentials'() {
    expect:
    SharedCommunicationObjects.parseHttpsProxy(configured)?.toString() == expected

    where:
    configured                      | expected
    null                            | null
    ""                              | null
    "proxy.example:8080"            | "http://proxy.example:8080/"
    "http://user:pass@proxy:3128"   | "http://user:pass@proxy:3128/"
    "https://unsupported.example"   | null
  }
}
