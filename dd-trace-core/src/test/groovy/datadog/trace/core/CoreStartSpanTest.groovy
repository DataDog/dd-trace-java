package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

class CoreStartSpanTest extends DDCoreSpecification {
  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  def cleanup() {
    tracer.close()
  }

  def "start a simple span"() {
    setup:
    final String spanName = "fakeName"

    when:
    DDSpan spanFromStart = tracer.startSpan(spanName, true)
    DDSpan spanFromBuilder = tracer.buildSpan(spanName).start()

    then:
    spanFromStart.getOperationName() == spanFromBuilder.getOperationName()
    spanFromStart.hasCheckpoints() == spanFromBuilder.hasCheckpoints()

    when:
    spanFromStart = tracer.startSpan(spanName, false)
    spanFromBuilder = tracer.buildSpan(spanName).suppressCheckpoints().start()

    then:
    spanFromStart.getOperationName() == spanFromBuilder.getOperationName()
    spanFromStart.hasCheckpoints() == spanFromBuilder.hasCheckpoints()
  }

  def "start a span with start timestamp"() {
    setup:
    // time in micro
    final long expectedTimestamp = 487517802L * 1000 * 1000L
    final String expectedName = "fakeName"

    when:
    DDSpan spanFromStart = tracer.startSpan(expectedName, expectedTimestamp, true)
    DDSpan spanFromBuilder = tracer.buildSpan(expectedName).withStartTimestamp(expectedTimestamp).start()

    then:
    spanFromStart.getOperationName() == spanFromBuilder.getOperationName()
    spanFromStart.hasCheckpoints() == spanFromBuilder.hasCheckpoints()
    spanFromStart.getStartTime() == spanFromBuilder.getStartTime()

  }

  def "start a span with parent"() {
    setup:
    final DDId spanId = DDId.ONE

    final DDSpanContext mockedContext = Mock()
    2 * mockedContext.getTraceId() >> spanId
    2 * mockedContext.getSpanId() >> spanId
    _ * mockedContext.getServiceName() >> "foo"
    2 * mockedContext.getBaggageItems() >> [:]
    2 * mockedContext.getTrace() >> tracer.pendingTraceFactory.create(DDId.ONE)

    final String expectedName = "fakeName"

    when:
    DDSpan spanFromStart = tracer.startSpan(expectedName, mockedContext, true)
    DDSpan spanFromBuilder =
      tracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .asChildOf(mockedContext)
      .start()

    then:
    spanFromStart.context().getParentId() == spanFromBuilder.context().getParentId()
    spanFromStart.context().getTraceId() == spanFromBuilder.context().getTraceId()
  }
}
