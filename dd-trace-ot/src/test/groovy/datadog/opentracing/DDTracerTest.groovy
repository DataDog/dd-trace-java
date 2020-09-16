package datadog.opentracing

import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.util.test.DDSpecification

class DDTracerTest extends DDSpecification {

  def "test tracer builder"() {
    when:
    def tracer = DDTracer.builder().build()

    then:
    tracer != null
  }

  def "test tracer builder with default writer"() {
    when:
    def tracer = DDTracer.builder().writer(DDAgentWriter.builder().build()).build()

    then:
    tracer != null
  }
}
