package datadog.trace.agent.integration.opentracing

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
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.scopeManager().activate(span, false)

    then:
    scope.span() == span

    cleanup:
    scope.close()
  }
}
