package datadog.opentracing

import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.test.util.DDSpecification

class DDTracerTest extends DDSpecification {

  def "test tracer builder"() {
    when:
    def tracer = DDTracer.builder().build()

    then:
    tracer != null

    cleanup:
    tracer.close()
  }

  def "test tracer builder with default writer"() {
    when:
    def tracer = DDTracer.builder().writer(DDAgentWriter.builder().build()).build()

    then:
    tracer != null

    cleanup:
    tracer.close()
  }
}
