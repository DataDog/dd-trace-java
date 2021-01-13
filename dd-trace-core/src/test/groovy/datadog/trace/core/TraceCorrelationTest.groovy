package datadog.trace.core

import datadog.trace.common.writer.ListWriter
import datadog.trace.test.util.DDSpecification

class TraceCorrelationTest extends DDSpecification {
  def tracer = CoreTracer.builder().writer(new ListWriter()).build()

  def span = tracer.buildSpan("test").start()
  def scope = tracer.activateSpan(span)

  def cleanup() {
    scope.close()
    span.finish()
    tracer.close()
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
