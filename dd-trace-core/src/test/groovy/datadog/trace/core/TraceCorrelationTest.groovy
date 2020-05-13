package datadog.trace.core


import datadog.trace.common.writer.ListWriter
import datadog.trace.util.test.DDSpecification
import spock.lang.Shared

class TraceCorrelationTest extends DDSpecification {

  static final WRITER = new ListWriter()

  @Shared
  CoreTracer tracer = CoreTracer.builder().writer(WRITER).build()

  def span = tracer.buildSpan("test").start()
  def scope = tracer.activateSpan(span)

  def cleanup() {
    scope.close()
    span.finish()
  }

  def "get trace id without trace"() {
    setup:
    scope.close()

    expect:
    "0" == tracer.getTraceId()
  }

  def "get trace id with trace"() {
    expect:
    ((DDSpan) scope.span()).traceId.toString() == tracer.getTraceId()
  }

  def "get span id without span"() {
    setup:
    scope.close()

    expect:
    "0" == tracer.getSpanId()
  }

  def "get span id with trace"() {
    expect:
    ((DDSpan) scope.span()).spanId.toString() == tracer.getSpanId()
  }
}
