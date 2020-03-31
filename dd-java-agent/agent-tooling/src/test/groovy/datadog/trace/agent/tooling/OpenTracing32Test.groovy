package datadog.trace.agent.tooling

import datadog.opentracing.DDSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.util.test.DDSpecification
import io.opentracing.noop.NoopSpan

class OpenTracing32Test extends DDSpecification {
  def setupSpec() {
    TracerInstaller.installGlobalTracer()
  }

  def "Start and activate spans on the normal path"() {
    given:
    def tracer = AgentTracer.get()

    when:
    def span = tracer.startSpan("test")

    then:
    span.spanName == "test"

    when:
    def scope = tracer.activateSpan(span, true)

    then:
    ((OpenTracing32.OT32Span) tracer.activeSpan()).span == ((OpenTracing32.OT32Span) span).span

    when:
    scope.close()

    then:
    ((DDSpan) ((OpenTracing32.OT32Span) span).span).getDurationNano() > 0

    and:
    tracer.activeSpan() == null
  }

  def "Start span and activate span then activate noop span"() {
    given:
    def tracer = AgentTracer.get()

    when:
    def span = tracer.startSpan("test")

    then:
    span.spanName == "test"

    when:
    def scope = tracer.activateSpan(span, true)

    then:
    ((OpenTracing32.OT32Span) tracer.activeSpan()).span == ((OpenTracing32.OT32Span) span).span

    when:
    def noopScope = tracer.activateSpan(null, true)

    then:
    ((OpenTracing32.OT32Span) tracer.activeSpan()).span == NoopSpan.INSTANCE
    tracer.activeScope() != null

    when:
    noopScope.close()

    then:
    ((OpenTracing32.OT32Span) tracer.activeSpan()).span == ((OpenTracing32.OT32Span) span).span

    when:
    scope.close()

    then:
    ((DDSpan) ((OpenTracing32.OT32Span) span).span).getDurationNano() > 0

    and:
    tracer.activeSpan() == null
  }
}
