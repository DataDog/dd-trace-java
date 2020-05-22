package datadog.trace.core.scopemanager

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.core.interceptor.TraceStatsCollector
import datadog.trace.profiling.Profiler
import datadog.trace.profiling.Session
import datadog.trace.profiling.SessionFactory
import datadog.trace.util.test.DDSpecification
import org.HdrHistogram.Histogram

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class TraceProfilingScopeInterceptorTest extends DDSpecification {
  def delegate = Mock(ScopeInterceptor)
  def delegateScope = Mock(ScopeInterceptor.Scope)
  def statsCollector = Mock(TraceStatsCollector)
  def span = Mock(AgentSpan)
  def factory = Mock(SessionFactory)
  def session = Mock(Session)

  def setup() {
    Profiler.initialize(factory)
  }

  def "validate Percentage scope lifecycle"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(rate, statsCollector, delegate)

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

    then: "nested interaction is ignored"
    if (isProfiling) {
      1 * delegate.handleSpan(span) >> delegateScope
      0 * _
    }

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    if (isProfiling) {
      1 * session.close()
    }
    0 * _

    where:
    rate | traceId      | isProfiling
    0.0  | 1g           | false
    0.5  | TRACE_ID_MAX | false
    0.5  | 1g           | true
    1.0  | 1g           | true
  }

  def "validate Heuristical scope lifecycle with no histogram"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(null, statsCollector, delegate)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    1 * delegate.handleSpan(_) >> delegateScope
    1 * span.getLocalRootSpan() >> span
    1 * statsCollector.getTraceStats(span) >> null
    0 * _

    when:
    scope.afterActivated()

    then:
    1 * delegateScope.afterActivated()
    0 * _

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    0 * _
  }

  def "validate Heuristical scope lifecycle with histogram"() {
    setup:
    Histogram overall = new Histogram(1)
    Histogram traceStats = new Histogram(1)
    for (int i = 0; i < overallCount; i++) {
      overall.recordValue(overallVal)
    }
    for (int i = 0; i < traceCount; i++) {
      traceStats.recordValue(traceVal)
    }

    def interceptor = TraceProfilingScopeInterceptor.create(null, statsCollector, delegate)
    if (simulateDelay) {
      (interceptor as TraceProfilingScopeInterceptor.Heuristical).lastProfileTimestamp =
        (interceptor as TraceProfilingScopeInterceptor.Heuristical).lastProfileTimestamp -
          (TraceProfilingScopeInterceptor.MAX_NANOSECONDS_BETWEEN_ACTIVATIONS * 2)
    }

    when:
    def scope = interceptor.handleSpan(span)

    then:
    1 * delegate.handleSpan(_) >> delegateScope
    1 * span.getLocalRootSpan() >> span
    1 * statsCollector.getTraceStats(span) >> traceStats
    1 * statsCollector.getOverallStats() >> overall
    if (isProfiling) {
      1 * span.getTraceId() >> 5g
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

    then: "nested interaction is ignored"
    if (isProfiling) {
      1 * delegate.handleSpan(span) >> delegateScope
      0 * _
    }

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    if (isProfiling) {
      1 * session.close()
    }
    0 * _

    where:
    overallVal | overallCount | traceVal | traceCount | simulateDelay | isProfiling
    0          | 0            | 0        | 0          | false         | false
    0          | 0            | 0        | 0          | true          | true
    1          | 100          | 1        | 1          | false         | false
    1          | 100          | 1        | 3          | false         | false
    1          | 100          | 1        | 90         | false         | false
    1          | 100          | 1        | 4          | false         | true
    50         | 1            | 50       | 1          | false         | false
    50         | 1            | 80       | 1          | false         | true
  }

  def "validate Heuristical rate limiting"() {
    setup:
    Histogram overall = new Histogram(1)
    Histogram traceStats = new Histogram(1)
    for (int i = 0; i < overallCount; i++) {
      overall.recordValue(overallVal)
    }
    for (int i = 0; i < traceCount; i++) {
      traceStats.recordValue(traceVal)
    }

    def interceptor = TraceProfilingScopeInterceptor.create(null, statsCollector, delegate)

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
    _ * statsCollector.getTraceStats(span) >> traceStats
    _ * statsCollector.getOverallStats() >> overall
    _ * span.getTraceId() >> 5g
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
