package datadog.trace.core.datastreams

import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.test.DDCoreSpecification

class StubDataStreamsCheckpointerTest extends DDCoreSpecification {
  def "extractPathway calls returns NoopPathwayContext"() {
    when:
    def checkpointer = new StubDataStreamsCheckpointer()

    then:
    checkpointer.extractPathwayContext(null, null) == AgentTracer.NoopPathwayContext.INSTANCE
    checkpointer.extractBinaryPathwayContext(null, null) == AgentTracer.NoopPathwayContext.INSTANCE
  }
}
