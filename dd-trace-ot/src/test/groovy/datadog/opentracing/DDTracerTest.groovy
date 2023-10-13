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

  def "test access to TraceSegment"() {
    when:
    def tracer = DDTracer.builder().writer(DDAgentWriter.builder().build()).build()
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span)

    then:
    tracer != null
    tracer.activeSpan().delegate == span.delegate

    when:
    def seg = tracer.getTraceSegment()

    then:
    seg != null
    scope.close()

    cleanup:
    tracer.close()
  }
}
