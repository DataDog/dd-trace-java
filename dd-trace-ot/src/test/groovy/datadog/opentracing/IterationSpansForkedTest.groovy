package datadog.opentracing

import datadog.metrics.api.statsd.StatsDClient
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import io.opentracing.ScopeManager

class IterationSpansForkedTest extends DDSpecification {
  ListWriter writer
  DDTracer tracer
  ScopeManager scopeManager
  StatsDClient statsDClient
  CoreTracer coreTracer

  def setup() {
    injectSysConfig("dd.trace.scope.iteration.keep.alive", "1")

    writer = new ListWriter()
    statsDClient = Mock()
    tracer = DDTracer.builder().writer(writer).statsDClient(statsDClient).build()
    scopeManager = tracer.scopeManager()
    coreTracer = tracer.tracer
  }

  def cleanup() {
    coreTracer.close()
  }

  def "root iteration scope lifecycle"() {
    when:
    coreTracer.closePrevious(true)
    def span1 = coreTracer.buildSpan("next1").start()
    def scope1 = coreTracer.activateNext(span1)

    then:
    writer.empty

    and:
    scope1.span() == span1
    scopeManager.active().span().delegate == span1
    !spanFinished(span1)

    when:
    coreTracer.closePrevious(true)
    def span2 = coreTracer.buildSpan("next2").start()
    def scope2 = coreTracer.activateNext(span2)

    then:
    spanFinished(span1)
    writer == [[span1]]

    and:
    scope2.span() == span2
    scopeManager.active().span().delegate == span2
    !spanFinished(span2)

    when:
    coreTracer.closePrevious(true)
    def span3 = coreTracer.buildSpan("next3").start()
    def scope3 = coreTracer.activateNext(span3)
    writer.waitForTraces(2)

    then:
    spanFinished(span2)
    writer == [[span1], [span2]]

    and:
    scope3.span() == span3
    scopeManager.active().span().delegate == span3
    !spanFinished(span3)

    when:
    // 'next3' should time out & finish after 1s
    writer.waitForTraces(3)

    then:
    spanFinished(span3)
    writer == [[span1], [span2], [span3]]
  }

  def "non-root iteration scope lifecycle"() {
    setup:
    def span0 = coreTracer.buildSpan("parent").start()
    def scope0 = coreTracer.activateSpan(span0)

    when:
    coreTracer.closePrevious(true)
    def span1 = coreTracer.buildSpan("next1").start()
    def scope1 = coreTracer.activateNext(span1)

    then:
    writer.empty

    and:
    scope1.span() == span1
    scopeManager.active().span().delegate == span1
    !spanFinished(span1)

    when:
    coreTracer.closePrevious(true)
    def span2 = coreTracer.buildSpan("next2").start()
    def scope2 = coreTracer.activateNext(span2)

    then:
    spanFinished(span1)
    writer.empty

    and:
    scope2.span() == span2
    scopeManager.active().span().delegate == span2
    !spanFinished(span2)

    when:
    coreTracer.closePrevious(true)
    def span3 = coreTracer.buildSpan("next3").start()
    def scope3 = coreTracer.activateNext(span3)

    then:
    spanFinished(span2)
    writer.empty

    and:
    scope3.span() == span3
    scopeManager.active().span().delegate == span3
    !spanFinished(span3)

    // close and finish the surrounding (non-iteration) span to complete the trace
    scope0.close()
    span0.finish()
    writer.waitForTraces(1)

    then:
    spanFinished(span3)
    spanFinished(span0)
    sortSpansByStart()
    writer == [[span0, span1, span2, span3]]
  }

  def "nested iteration scope lifecycle"() {
    when:
    coreTracer.closePrevious(true)
    def span1 = coreTracer.buildSpan("next1").start()
    def scope1 = coreTracer.activateNext(span1)

    then:
    writer.empty

    and:
    scope1.span() == span1
    scopeManager.active().span().delegate == span1
    !spanFinished(span1)

    when:
    def span1A = coreTracer.buildSpan("methodA").start()
    def scope1A = coreTracer.activateSpan(span1A)

    and:
    coreTracer.closePrevious(true)
    def span1A1 = coreTracer.buildSpan("next1A1").start()
    def scope1A1 = coreTracer.activateNext(span1A1)

    then:
    !spanFinished(span1)
    writer.empty

    and:
    scope1A1.span() == span1A1
    scopeManager.active().span().delegate == span1A1
    !spanFinished(span1A1)

    when:
    coreTracer.closePrevious(true)
    def span1A2 = coreTracer.buildSpan("next1A2").start()
    def scope1A2 = coreTracer.activateNext(span1A2)

    then:
    spanFinished(span1A1)
    writer.empty

    and:
    scope1A2.span() == span1A2
    scopeManager.active().span().delegate == span1A2
    !spanFinished(span1A2)

    when:
    // close and finish the intermediate (non-iteration) span
    scope1A.close()
    span1A.finish()
    // 'next1' (and 'next1A2') should time out & finish after 1s to complete the trace
    writer.waitForTraces(1)

    then:
    spanFinished(span1A2)
    spanFinished(span1A)
    spanFinished(span1)
    sortSpansByStart()
    writer == [[span1, span1A, span1A1, span1A2]]
  }

  boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span)?.isFinished()
  }

  private List<DDSpan> sortSpansByStart() {
    writer.firstTrace().sort { a, b ->
      return a.startTimeNano <=> b.startTimeNano
    }
  }
}
