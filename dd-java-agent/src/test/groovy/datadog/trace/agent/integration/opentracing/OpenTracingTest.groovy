package datadog.trace.agent.integration.opentracing

import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.context.TraceScope
import io.opentracing.util.GlobalTracer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Simple set of tests to verify the OpenTracing instrumentation is added correctly.
 */
class OpenTracingTest extends Specification {

  @Subject
  def tracer = GlobalTracer.get()

  def "test tracer registered"() {
    expect:
    GlobalTracer.isRegistered()
  }

  def "test span/scope interfaces"() {
    setup:
    def span = tracer.buildSpan("test").start()

    expect:
    span instanceof MutableSpan

    when:
    def scope = tracer.scopeManager().activate(span, false)

    then:
    scope instanceof TraceScope

    cleanup:
    scope.close()
  }
}
