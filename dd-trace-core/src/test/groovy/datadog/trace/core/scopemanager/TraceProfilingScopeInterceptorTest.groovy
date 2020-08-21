package datadog.trace.core.scopemanager

import com.timgroup.statsd.StatsDClient
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator
import datadog.trace.mlt.MethodLevelTracer
import datadog.trace.mlt.Session
import datadog.trace.mlt.SessionFactory
import datadog.trace.util.test.DDSpecification

import static datadog.trace.agent.test.utils.ConfigUtils.withConfigOverride
import static datadog.trace.api.config.TracerConfig.METHOD_TRACE_ENABLED
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class TraceProfilingScopeInterceptorTest extends DDSpecification {
  def traceEvaluator = Mock(TraceHeuristicsEvaluator)
  def statsDClient = Mock(StatsDClient)
  def span = Mock(AgentSpan)
  def factory = Mock(SessionFactory)
  def session = Mock(Session)

  def setupSpec() {
    ConfigUtils.updateConfig {
      System.setProperty("dd.method.trace.enabled", "true")
    }
  }

  def cleanupSpec() {
    ConfigUtils.updateConfig {
      System.clearProperty("dd.method.trace.enabled")
    }
  }

  def setup() {
    assert !TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get()
    MethodLevelTracer.initialize(factory)
  }

  def cleanup() {
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.set(false)
  }

  def "interceptor can be disabled"() {
    when:
    def interceptor = withConfigOverride(METHOD_TRACE_ENABLED, "false") {
      TraceProfilingScopeInterceptor.create(null, traceEvaluator, statsDClient)
    }

    then:
    interceptor instanceof TraceProfilingScopeInterceptor.NoOp
  }

  def "validate Percentage scope lifecycle"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(rate, traceEvaluator, statsDClient)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get() == isProfiling
    scope instanceof TraceProfilingScopeInterceptor.TraceProfilingScope == isProfiling
    if (isProfiling) {
      2 * span.getTraceId() >> traceId
      1 * factory.createSession("$traceId", _) >> session
      1 * statsDClient.incrementCounter('mlt.scope', 'scope:root')
    } else {
      1 * span.getTraceId() >> traceId
    }
    0 * _

    when: "already profiling"
    if (isProfiling) {
      interceptor.handleSpan(span)
    }

    then: "nested interaction is permitted"
    if (isProfiling) {
      1 * span.getTraceId() >> traceId
      1 * factory.createSession("$traceId", _) >> session
      1 * statsDClient.incrementCounter('mlt.scope', 'scope:child')
      0 * _
    }

    when:
    scope.close()

    then:
    !TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get()
    if (isProfiling) {
      1 * session.close() >> new byte[0]
      1 * statsDClient.incrementCounter('mlt.count')
      1 * statsDClient.count('mlt.bytes', 0)
      1 * span.setTag(InstrumentationTags.DD_MLT, new byte[0])
      1 * span.getSamplingPriority()
      1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
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
    def interceptor = TraceProfilingScopeInterceptor.create(null, traceEvaluator, statsDClient)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get() == isProfiling
    1 * span.getLocalRootSpan() >> span
    1 * traceEvaluator.isDistinctive(span) >> distinctive
    if (!distinctive) {
      1 * span.getSamplingPriority() >> priority
    }
    if (isProfiling) {
      1 * span.getTraceId() >> DDId.from(5)
      1 * factory.createSession("5", _) >> session
      1 * statsDClient.incrementCounter('mlt.scope', 'scope:root')
    }
    0 * _

    when: "already profiling"
    if (isProfiling) {
      interceptor.handleSpan(span)
    }

    then: "nested interaction is permitted"
    if (isProfiling) {
      1 * span.getTraceId() >> DDId.from(5)
      1 * factory.createSession("5", _) >> session
      1 * statsDClient.incrementCounter('mlt.scope', 'scope:child')
      0 * _
    }

    when:
    scope.close()

    then:
    !TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get()
    if (isProfiling) {
      1 * session.close() >> new byte[0]
      1 * statsDClient.incrementCounter('mlt.count')
      1 * statsDClient.count("mlt.bytes", 0)
      1 * span.setTag(InstrumentationTags.DD_MLT, new byte[0])
      1 * span.getSamplingPriority() >> priority
      if (priority == null) {
        1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
      }
    }
    0 * _

    where:
    distinctive | priority                      | isProfiling
    true        | null                          | true
    false       | PrioritySampling.SAMPLER_KEEP | false
    false       | PrioritySampling.USER_KEEP    | true
  }

  def "validate threadlocal triggered profiling"() {
    setup:
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.set(true)
    def interceptor = TraceProfilingScopeInterceptor.create(rate, traceEvaluator, statsDClient)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get()
    1 * span.getTraceId() >> DDId.from(5)
    1 * factory.createSession("5", _) >> session
    1 * statsDClient.incrementCounter('mlt.scope', 'scope:child')
    0 * _

    when:
    scope.close()

    then:
    TraceProfilingScopeInterceptor.IS_THREAD_PROFILING.get()
    1 * session.close() >> new byte[0]
    1 * statsDClient.incrementCounter('mlt.count')
    1 * statsDClient.count("mlt.bytes", 0)
    1 * span.setTag(InstrumentationTags.DD_MLT, new byte[0])
    1 * span.getSamplingPriority() >> null
    1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    0 * _

    where:
    rate | _
    null | _
    0.0  | _
  }

  def "validate Heuristical rate limiting"() {
    setup:
    def interceptor = TraceProfilingScopeInterceptor.create(null, traceEvaluator, statsDClient)

    when:
    def activations = 0
    for (int i = 0; i < 100; i++) {
      Thread.sleep(10) // Slow it down a bit
      def scope = interceptor.handleSpan(span)
      if (scope instanceof TraceProfilingScopeInterceptor.TraceProfilingScope) {
        activations++
      }
      scope.close()
    }

    then:
    TraceProfilingScopeInterceptor.ACTIVATIONS_PER_SECOND < activations
    activations < TraceProfilingScopeInterceptor.ACTIVATIONS_PER_SECOND * 3
    _ * span.getLocalRootSpan() >> span
    _ * traceEvaluator.isDistinctive(span) >> true
    _ * span.getTraceId() >> DDId.from(5)
    _ * factory.createSession("5", _) >> session
    _ * statsDClient.incrementCounter('mlt.scope', 'scope:root')
    _ * session.close()
    0 * _

    where:
    overallVal | overallCount | traceVal | traceCount
    1          | 100          | 1        | 4
    50         | 1            | 80       | 1
  }
}
