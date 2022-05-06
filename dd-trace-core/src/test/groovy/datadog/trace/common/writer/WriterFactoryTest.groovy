package datadog.trace.common.writer

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.api.StatsDClient
import datadog.trace.common.sampling.Sampler
import datadog.trace.common.writer.ddagent.Prioritization
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
}
