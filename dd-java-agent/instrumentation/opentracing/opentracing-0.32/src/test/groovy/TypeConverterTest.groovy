import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.instrumentation.opentracing.DefaultLogHandler
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.instrumentation.opentracing32.TypeConverter

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext

class TypeConverterTest extends InstrumentationSpecification {
  TypeConverter typeConverter = new TypeConverter(new DefaultLogHandler())

  def "should avoid the noop span wrapper allocation"() {
    def noopAgentSpan = noopSpan()
    expect:
    typeConverter.toSpan(noopAgentSpan) is typeConverter.toSpan(noopAgentSpan)
  }

  def "should avoid extra allocation for a span wrapper"() {
    def context = createTestSpanContext()
    def span1 = new DDSpan("test", 0, context, null)
    def span2 = new DDSpan("test", 0, context, null)
    expect:
    // return the same wrapper for the same span
    typeConverter.toSpan(span1) is typeConverter.toSpan(span1)
    // return a distinct wrapper for another span
    !typeConverter.toSpan(span1).is(typeConverter.toSpan(span2))
  }

  def "should avoid the noop context wrapper allocation"() {
    def noopContext = noopSpanContext()
    expect:
    typeConverter.toSpanContext(noopContext) is typeConverter.toSpanContext(noopContext)
  }

  def "should reuse the noop span wrapper via scope"() {
    def noopScope = Stub(AgentScope) {
      span() >> noopSpan()
    }
    def noopSpanWrapper = typeConverter.toSpan(noopSpan())
    expect:
    typeConverter.toScope(noopScope, true).span() is noopSpanWrapper
    typeConverter.toScope(noopScope, false).span() is noopSpanWrapper
  }

  def createTestSpanContext() {
    def trace = Stub(PendingTrace)
    return new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      [:],
      false,
      "fakeType",
      0,
      trace,
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty()) {
        @Override void setServiceName(final String serviceName) {
          // override this method that is called from the DDSpanContext constructor
          // because it causes NPE when calls trace.getTracer from within setServiceName
        }
      }
  }
}
