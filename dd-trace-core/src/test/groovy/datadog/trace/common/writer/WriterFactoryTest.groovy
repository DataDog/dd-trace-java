package datadog.trace.common.writer

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.common.sampling.Sampler
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.Prioritization
import datadog.trace.common.writer.ddintake.DDEvpProxyApi
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification

import java.util.stream.Collectors

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE

class WriterFactoryTest extends DDSpecification {

  def "test writer creation for #configuredType when agentHasEvpProxy=#hasEvpProxy evpProxySupportsCompression=#evpProxySupportsCompression ciVisibilityAgentless=#isCiVisibilityAgentlessEnabled"() {
    setup:
    def config = Mock(Config)
    config.apiKey >> "my-api-key"
    config.agentUrl >> "http://my-agent.url"
    config.getEnumValue(PRIORITIZATION_TYPE, _, _) >> Prioritization.FAST_LANE
    config.tracerMetricsEnabled >> true
    config.isCiVisibilityEnabled() >> true
    config.isCiVisibilityCodeCoverageEnabled() >> false

    def agentFeaturesDiscovery = Mock(DDAgentFeaturesDiscovery)
    agentFeaturesDiscovery.getEvpProxyEndpoint() >> DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT
    agentFeaturesDiscovery.supportsContentEncodingHeadersWithEvpProxy() >> evpProxySupportsCompression

    def sharedComm = new SharedCommunicationObjects()
    sharedComm.setFeaturesDiscovery(agentFeaturesDiscovery)
    sharedComm.createRemaining(config)

    def sampler = Mock(Sampler)

    when:
    agentFeaturesDiscovery.supportsEvpProxy() >> hasEvpProxy
    config.ciVisibilityAgentlessEnabled >> isCiVisibilityAgentlessEnabled
    def writer = WriterFactory.createWriter(config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType)

    def apis
    def apiClasses
    if (expectedApiClasses != null) {
      apis = ((RemoteWriter) writer).apis
      apiClasses = apis.stream().map(Object::getClass).collect(Collectors.toList())
    } else {
      apis = Collections.emptyList()
      apiClasses = Collections.emptyList()
    }

    then:
    writer.class == expectedWriterClass
    expectedApiClasses == null || apiClasses == expectedApiClasses
    expectedApiClasses == null || apis.stream().allMatch(api -> api.isCompressionEnabled() == isCompressionEnabled)

    where:
    configuredType                             | hasEvpProxy | evpProxySupportsCompression | isCiVisibilityAgentlessEnabled | expectedWriterClass  | expectedApiClasses | isCompressionEnabled
    "LoggingWriter"                            | true        | false                       | true                           | LoggingWriter        | null               | false
    "PrintingWriter"                           | true        | false                       | true                           | PrintingWriter       | null               | false
    "TraceStructureWriter"                     | true        | false                       | true                           | TraceStructureWriter | null               | false
    "MultiWriter:LoggingWriter,PrintingWriter" | true        | false                       | true                           | MultiWriter          | null               | false
    "DDIntakeWriter"                           | true        | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDIntakeWriter"                           | true        | false                       | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | false
    "DDIntakeWriter"                           | false       | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDIntakeWriter"                           | false       | false                       | false                          | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDAgentWriter"                            | true        | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDAgentWriter"                            | true        | false                       | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | false
    "DDAgentWriter"                            | true        | true                        | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | true
    "DDAgentWriter"                            | false       | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDAgentWriter"                            | false       | false                       | false                          | DDAgentWriter        | [DDAgentApi]       | false
    "not-found"                                | true        | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "not-found"                                | true        | false                       | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | false
    "not-found"                                | false       | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "not-found"                                | false       | false                       | false                          | DDAgentWriter        | [DDAgentApi]       | false
  }
}
