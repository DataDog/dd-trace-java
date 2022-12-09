package datadog.trace.common.writer

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.api.StatsDClient
import datadog.trace.common.sampling.Sampler
import datadog.trace.common.writer.ddagent.Prioritization
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddintake.DDEvpProxyApi
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE

class WriterFactoryTest extends DDSpecification {

  def "test writer creation for #configuredType"() {
    setup:
    def config = Mock(Config)
    config.apiKey >> "my-api-key"
    config.agentUrl >> "http://my-agent.url"
    config.getEnumValue(PRIORITIZATION_TYPE, _, _) >> Prioritization.FAST_LANE
    config.tracerMetricsEnabled >> true

    def sharedComm = new SharedCommunicationObjects()
    sharedComm.createRemaining(config)

    def sampler = Mock(Sampler)
    def statsd = Mock(StatsDClient)

    when:
    def writer = WriterFactory.createWriter(config, sharedComm, sampler, statsd, configuredType)

    then:
    writer.class == expectedClass

    where:
    configuredType | expectedClass
    "LoggingWriter"| LoggingWriter
    "PrintingWriter" | PrintingWriter
    "TraceStructureWriter" | TraceStructureWriter
    "MultiWriter:LoggingWriter,PrintingWriter" | MultiWriter
    "DDIntakeWriter" | DDIntakeWriter
    "DDAgentWriter" | DDAgentWriter
    "not-found" | DDAgentWriter
  }

  def "test override for civisibility for configuredWriterType=#configuredType hasEvpProxy=#hasEvpProxy agentless=#isCiVisibilityAgentlessEnabled"() {
    setup:
    def config = Mock(Config)
    config.apiKey >> "my-api-key"
    config.agentUrl >> "http://my-agent.url"
    config.getEnumValue(PRIORITIZATION_TYPE, _, _) >> Prioritization.FAST_LANE
    config.tracerMetricsEnabled >> true
    config.isCiVisibilityEnabled() >> true
    config.isCiVisibilityAgentlessEnabled() >> isCiVisibilityAgentlessEnabled

    def agentFeaturesDiscovery = Mock(DDAgentFeaturesDiscovery)
    agentFeaturesDiscovery.getEvpProxyEndpoint() >> DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT
    agentFeaturesDiscovery.supportsEvpProxy() >> hasEvpProxy

    def sharedComm = new SharedCommunicationObjects()
    sharedComm.setFeaturesDiscovery(agentFeaturesDiscovery)
    sharedComm.createRemaining(config)

    def sampler = Mock(Sampler)
    def statsd = Mock(StatsDClient)

    when:
    def writer = WriterFactory.createWriter(config, sharedComm, sampler, statsd, configuredType)

    then:
    writer.class == expectedWriterClass
    writer.api.class == expectedApiClass

    where:
    configuredType   | hasEvpProxy | isCiVisibilityAgentlessEnabled | expectedWriterClass | expectedApiClass
    "DDIntakeWriter" | true  | true  | DDIntakeWriter | DDIntakeApi
    "DDIntakeWriter" | true  | false | DDIntakeWriter | DDEvpProxyApi
    "DDIntakeWriter" | false | true  | DDIntakeWriter | DDIntakeApi
    "DDIntakeWriter" | false | false | DDIntakeWriter | DDIntakeApi
    "DDAgentWriter"  | true  | true  | DDIntakeWriter | DDIntakeApi
    "DDAgentWriter"  | true  | false | DDIntakeWriter | DDEvpProxyApi
    "DDAgentWriter"  | false | true  | DDIntakeWriter | DDIntakeApi
    "DDAgentWriter"  | false | false | DDAgentWriter  | DDAgentApi
    "not-found"      | true  | true  | DDIntakeWriter | DDIntakeApi
    "not-found"      | true  | false | DDIntakeWriter | DDEvpProxyApi
    "not-found"      | false | true  | DDIntakeWriter | DDIntakeApi
    "not-found"      | false | false | DDAgentWriter  | DDAgentApi
  }
}
