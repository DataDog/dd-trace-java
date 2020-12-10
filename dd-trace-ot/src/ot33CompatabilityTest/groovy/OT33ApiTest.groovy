import datadog.opentracing.DDTracer
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import io.opentracing.Tracer
import spock.lang.Subject

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

// This test focuses on things that are different between OpenTracing API 0.32.0 and 0.33.0
class OT33ApiTest extends DDSpecification {
  def writer = new ListWriter()

  @Subject
  Tracer tracer = DDTracer.builder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "test start"() {
    when:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.activateSpan(span)
    scope.close()

    then:
    (scope.span().delegate as DDSpan).isFinished() == false
    assertTraces(writer, 0) {}

    when:
    span.finish()

    then:
    assertTraces(writer, 1) {
      trace(1) {
        basicSpan(it, "some name")
      }
    }
  }

  def "test scopemanager"() {
    setup:
    def span = tracer.buildSpan("some name").start()

    when:
    tracer.scopeManager().activate(span) != null

    then:
    tracer.activeSpan().delegate == span.delegate
  }
}
