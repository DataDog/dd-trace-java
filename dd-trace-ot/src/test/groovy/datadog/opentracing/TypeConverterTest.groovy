package datadog.opentracing

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.test.util.DDSpecification

class TypeConverterTest extends DDSpecification {
  TypeConverter typeConverter = new TypeConverter(new DefaultLogHandler())

  def "should avoid the noop span wrapper allocation"() {
    def noopAgentSpan = AgentTracer.NoopAgentSpan.INSTANCE
    expect:
    typeConverter.toSpan(noopAgentSpan) is typeConverter.toSpan(noopAgentSpan)
  }

  def "should avoid extra allocation for a span wrapper"() {
    def context = createTestSpanContext()
    def span1 = new DDSpan(0, context)
    def span2 = new DDSpan(0, context)
    expect:
    // return the same wrapper for the same span
    typeConverter.toSpan(span1) is typeConverter.toSpan(span1)
    // return a distinct wrapper for another span
    !typeConverter.toSpan(span1).is(typeConverter.toSpan(span2))
  }

  def "should avoid the noop context wrapper allocation"() {
    def noopContext = AgentTracer.NoopContext.INSTANCE
    expect:
    typeConverter.toSpanContext(noopContext) is typeConverter.toSpanContext(noopContext)
  }

  def "should avoid extra allocation for a context wrapper"() {
    def context1 = createTestSpanContext()
    def context2 = createTestSpanContext()
    expect:
    // return the same wrapper for the same context
    typeConverter.toSpanContext(context1) is typeConverter.toSpanContext(context1)
    // return distinct wrapper for another context
    !typeConverter.toSpanContext(context1).is(typeConverter.toSpanContext(context2))
  }

  def createTestSpanContext() {
    def tracer = Mock(CoreTracer)
    tracer.mapServiceName(_) >> { String serviceName -> serviceName }
    def trace = Mock(PendingTrace)
    trace.getTracer() >> tracer

    return new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      SamplingMechanism.UNKNOWN,
      null,
      [:],
      false,
      "fakeType",
      0,
      trace,
      null,
      false)
  }
}
