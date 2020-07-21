package datadog.trace.core.scopemanager

import com.timgroup.statsd.StatsDClient
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator
import datadog.trace.mlt.MethodLevelTracer
import datadog.trace.mlt.Session
import datadog.trace.mlt.SessionFactory
import datadog.trace.util.test.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class TraceProfilingScopeInterceptorTest extends DDSpecification {
  def delegate = Mock(ScopeInterceptor)
  def delegateScope = Mock(ScopeInterceptor.Scope)
  def traceEvaluator = Mock(TraceHeuristicsEvaluator)
  def statsDClient = Mock(StatsDClient)
  def span = Mock(AgentSpan)
  def factory = Mock(SessionFactory)
  def session = Mock(Session)

  def setup() {
    assert !TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get()
    MethodLevelTracer.initialize(factory)
  }

  def cleanup() {
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.set(false)
  }

  def "validate Percentage scope lifecycle"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(rate, traceEvaluator, statsDClient, delegate)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    scope instanceof TraceProfilingScopeInterceptor.TraceProfilingScope == isProfiling
    1 * delegate.handleSpan(span) >> delegateScope
    if (isProfiling) {
      2 * span.getTraceId() >> traceId
      1 * factory.createSession("$traceId", _) >> session
    } else {
      1 * span.getTraceId() >> traceId
    }
    0 * _

    when:
    scope.afterActivated()

    then:
    1 * delegateScope.afterActivated()
    0 * _

    when: "already profiling"
    if (isProfiling) {
      interceptor.handleSpan(span)
    }

    then: "nested interaction is permitted"
    if (isProfiling) {
      1 * delegate.handleSpan(span) >> delegateScope
      1 * span.getTraceId() >> traceId
      1 * factory.createSession("$traceId", _) >> session
      0 * _
    }

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    if (isProfiling) {
      1 * session.close() >> new byte[0]
      1 * statsDClient.incrementCounter('mlt.count')
      1 * statsDClient.count('mlt.bytes', 0)
      1 * delegateScope.span() >> span
      1 * span.setTag(InstrumentationTags.DD_MLT, new byte[0])
    }
    0 * _

    where:
    rate | tid          | isProfiling
    0.0  | 1g           | false
    0.5  | TRACE_ID_MAX | false
    0.5  | 1g           | true
    1.0  | 1g           | true

    traceId = DDId.from(tid.longValue())
  }

  def "validate Heuristical scope lifecycle"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(null, traceEvaluator, statsDClient, delegate)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    1 * delegate.handleSpan(_) >> delegateScope
    1 * span.getLocalRootSpan() >> span
    1 * traceEvaluator.isDistinctive(span) >> isProfiling
    if (isProfiling) {
      1 * span.getTraceId() >> DDId.from(5)
      1 * factory.createSession("5", _) >> session
    }
    0 * _

    when:
    scope.afterActivated()

    then:
    1 * delegateScope.afterActivated()
    0 * _

    when: "already profiling"
    if (isProfiling) {
      interceptor.handleSpan(span)
    }

    then: "nested interaction is permitted"
    if (isProfiling) {
      1 * delegate.handleSpan(_) >> delegateScope
      1 * span.getTraceId() >> DDId.from(5)
      1 * factory.createSession("5", _) >> session
      0 * _
    }

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    if (isProfiling) {
      1 * session.close() >> new byte[0]
      1 * statsDClient.incrementCounter('mlt.count')
      1 * statsDClient.count("mlt.bytes", 0)
      1 * delegateScope.span() >> span
      1 * span.setTag(InstrumentationTags.DD_MLT, new byte[0])
    }
    0 * _

    where:
    isProfiling << [true, false]
  }

  def "validate Heuristical rate limiting"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(null, traceEvaluator, statsDClient, delegate)

    when:
    def activations = 0
    for (int i = 0; i < 25; i++) {
      Thread.sleep(100) // Slow it down a bit
      def scope = interceptor.handleSpan(span)
      if (scope instanceof TraceProfilingScopeInterceptor.TraceProfilingScope) {
        activations++
      }
      scope.close()
    }

    then:
    TraceProfilingScopeInterceptor.ACTIVATIONS_PER_SECOND < activations
    activations < TraceProfilingScopeInterceptor.ACTIVATIONS_PER_SECOND * 3
    _ * delegate.handleSpan(_) >> delegateScope
    _ * span.getLocalRootSpan() >> span
    _ * traceEvaluator.isDistinctive(span) >> true
    _ * span.getTraceId() >> DDId.from(5)
    _ * factory.createSession("5", _) >> session
    _ * delegateScope.close()
    _ * session.close()
    0 * _

    where:
    overallVal | overallCount | traceVal | traceCount
    1          | 100          | 1        | 4
    50         | 1            | 80       | 1
  }
}
