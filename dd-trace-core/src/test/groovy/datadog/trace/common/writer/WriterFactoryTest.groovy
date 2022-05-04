package datadog.trace.common.writer

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.api.StatsDClient
import datadog.trace.common.sampling.Sampler
import datadog.trace.test.util.DDSpecification

class WriterFactoryTest extends DDSpecification {

  def "test direct writer creation"() {
    setup:
    def config = Mock(Config)
    def sharedComm = Mock(SharedCommunicationObjects)
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
  }
}
