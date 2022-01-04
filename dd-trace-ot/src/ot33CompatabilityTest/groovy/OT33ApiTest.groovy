import datadog.opentracing.DDTracer
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import io.opentracing.Tracer
import io.opentracing.util.ThreadLocalScopeManager
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
    def coreTracer = tracer.tracer

    when:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span)

    then:
    tracer.activeSpan().delegate == span.delegate
    coreTracer.activeScope().span() == span.delegate
    coreTracer.activeSpan() == span.delegate

    cleanup:
    scope.close()
    span.finish()
  }

  def "test custom scopemanager"() {
    setup:
    def customTracer = DDTracer.builder().writer(writer).scopeManager(new ThreadLocalScopeManager()).build()
    def coreTracer = customTracer.tracer

    when:
    def span = customTracer.buildSpan("some name").start()
    def scope = customTracer.scopeManager().activate(span)

    then:
    customTracer.activeSpan().delegate == span.delegate
    coreTracer.activeScope().span() == span.delegate
    coreTracer.activeSpan() == span.delegate

    cleanup:
    scope.close()
    span.finish()
  }
}
