package datadog.opentracing


import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.ListWriter
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

  def "should produce blackhole scopes"() {
    setup:
    def writer = new ListWriter()
    def tracer = DDTracer.builder().writer(writer).build()

    when:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span)
    def muteScope = tracer.muteTracing()
    def blackholed = tracer.buildSpan("hidden span").start()
    blackholed.finish()
    muteScope.close()
    def visibleSpan = tracer.buildSpan("visible span").start()
    visibleSpan.finish()
    scope.close()
    span.finish()

    then:
    writer.waitForTraces(1)
    assert writer.size() == 1
    assert writer.firstTrace().size() == 2
    assert Long.toString(writer.firstTrace()[0].context().getSpanId()) == span.context().toSpanId()
    assert Long.toString(writer.firstTrace()[1].context().getSpanId()) == visibleSpan.context().toSpanId()

    cleanup:
    tracer.close()
  }
}
