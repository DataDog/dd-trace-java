import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDId
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.scopemanager.ContinuableScopeManager
import datadog.trace.instrumentation.opentracing.DefaultLogHandler
import datadog.trace.instrumentation.opentracing31.TypeConverter

class TypeConverterTest extends AgentTestRunner {
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

  def "should avoid the noop scope wrapper allocation"() {
    def noopScope = AgentTracer.NoopAgentScope.INSTANCE
    expect:
    typeConverter.toScope(noopScope, true) is typeConverter.toScope(noopScope, true)
    typeConverter.toScope(noopScope, false) is typeConverter.toScope(noopScope, false)
    // noop scopes expected to be the same despite the finishSpanOnClose flag
    typeConverter.toScope(noopScope, true) is typeConverter.toScope(noopScope, false)
    typeConverter.toScope(noopScope, false) is typeConverter.toScope(noopScope, true)
  }

  def "should avoid extra allocation for a scope wrapper"() {
    def scopeManager = new ContinuableScopeManager(0, StatsDClient.NO_OP, false, true)
    def context = createTestSpanContext()
    def span1 = new DDSpan(0, context)
    def span2 = new DDSpan(0, context)
    def scope1 = scopeManager.activate(span1, ScopeSource.MANUAL)
    def scope2 = scopeManager.activate(span2, ScopeSource.MANUAL)
    expect:
    // return the same wrapper for the same scope
    typeConverter.toScope(scope1, true) is typeConverter.toScope(scope1, true)
    typeConverter.toScope(scope1, false) is typeConverter.toScope(scope1, false)
    !typeConverter.toScope(scope1, true).is(typeConverter.toScope(scope1, false))
    !typeConverter.toScope(scope1, false).is(typeConverter.toScope(scope1, true))
    // return distinct wrapper for another context
    !typeConverter.toScope(scope1, true).is(typeConverter.toScope(scope2, true))
  }

  def createTestSpanContext() {
    def trace = Mock(PendingTrace)
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
      false) {
        @Override void setServiceName(final String serviceName) {
          // override this method that is called from the DDSpanContext constructor
          // because it causes NPE when calls trace.getTracer from within setServiceName
        }
      }
  }
}
