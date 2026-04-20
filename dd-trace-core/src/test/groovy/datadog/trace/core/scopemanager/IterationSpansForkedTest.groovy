package datadog.trace.core.scopemanager

import datadog.metrics.api.statsd.StatsDClient
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification

class IterationSpansForkedTest extends DDCoreSpecification {

  ListWriter writer
  CoreTracer tracer
  ContinuableScopeManager scopeManager
  StatsDClient statsDClient

  def setup() {
    injectSysConfig("dd.trace.scope.iteration.keep.alive", "1")

    writer = new ListWriter()
    statsDClient = Mock()
    tracer = tracerBuilder().writer(writer).statsDClient(statsDClient).build()
    scopeManager = tracer.scopeManager
  }

  def cleanup() {
    tracer.close()
  }

  def "root iteration scope lifecycle"() {
    when:
    tracer.closePrevious(true)
    def span1 = tracer.buildSpan("next1").start()
    def scope1 = tracer.activateNext(span1)

    then:
    writer.empty

    and:
    scope1.span() == span1
    scopeManager.active() == scope1
    !spanFinished(span1)

    when:
    tracer.closePrevious(true)
    def span2 = tracer.buildSpan("next2").start()
    def scope2 = tracer.activateNext(span2)

    then:
    spanFinished(span1)
    writer == [[span1]]

    and:
    scope2.span() == span2
    scopeManager.active() == scope2
    !spanFinished(span2)

    when:
    tracer.closePrevious(true)
    def span3 = tracer.buildSpan("next3").start()
    def scope3 = tracer.activateNext(span3)

    then:
    spanFinished(span2)
    writer == [[span1], [span2]]

    and:
    scope3.span() == span3
    scopeManager.active() == scope3
    !spanFinished(span3)

    when:
    // 'next3' should time out & finish after 1s
    writer.waitForTraces(3)

    then:
    spanFinished(span3)
    writer == [[span1], [span2], [span3]]

    and:
    scopeManager.active() == null
  }

  def "non-root iteration scope lifecycle"() {
    setup:
    def span0 = tracer.buildSpan("parent").start()
    def scope0 = tracer.activateSpan(span0)

    when:
    tracer.closePrevious(true)
    def span1 = tracer.buildSpan("next1").start()
    def scope1 = tracer.activateNext(span1)

    then:
    writer.empty

    and:
    scope1.span() == span1
    scopeManager.active() == scope1
    !spanFinished(span1)

    when:
    tracer.closePrevious(true)
    def span2 = tracer.buildSpan("next2").start()
    def scope2 = tracer.activateNext(span2)

    then:
    spanFinished(span1)
    writer.empty

    and:
    scope2.span() == span2
    scopeManager.active() == scope2
    !spanFinished(span2)

    when:
    tracer.closePrevious(true)
    def span3 = tracer.buildSpan("next3").start()
    def scope3 = tracer.activateNext(span3)

    then:
    spanFinished(span2)
    writer.empty

    and:
    scope3.span() == span3
    scopeManager.active() == scope3
    !spanFinished(span3)

    when:
    scope0.close()
    span0.finish()
    // closing the parent scope will close & finish 'next3'
    writer.waitForTraces(1)

    then:
    spanFinished(span3)
    spanFinished(span0)
    sortSpansByStart()
    writer == [[span0, span1, span2, span3]]

    and:
    scopeManager.active() == null
  }

  def "nested iteration scope lifecycle"() {
    when:
    tracer.closePrevious(true)
    def span1 = tracer.buildSpan("next").start()
    def scope1 = tracer.activateNext(span1)

    then:
    writer.empty

    and:
    scope1.span() == span1
    scopeManager.active() == scope1
    !spanFinished(span1)

    when:
    def span1A = tracer.buildSpan("method").start()
    def scope1A = tracer.activateSpan(span1A)

    and:
    tracer.closePrevious(true)
    def span1A1 = tracer.buildSpan("next").start()
    def scope1A1 = tracer.activateNext(span1A1)

    then:
    !spanFinished(span1)
    writer.empty

    and:
    scope1A1.span() == span1A1
    scopeManager.active() == scope1A1
    !spanFinished(span1A1)

    when:
    tracer.closePrevious(true)
    def span1A2 = tracer.buildSpan("next").start()
    def scope1A2 = tracer.activateNext(span1A2)

    then:
    spanFinished(span1A1)
    writer.empty

    and:
    scope1A2.span() == span1A2
    scopeManager.active() == scope1A2
    !spanFinished(span1A2)

    when:
    scope1A.close()
    span1A.finish()
    // closing the intervening scope will close & finish 'next1_2'

    then:
    spanFinished(span1A2)
    spanFinished(span1A)
    !spanFinished(span1)
    writer.empty

    when:
    // 'next1' should time out & finish after 1s to complete the trace
    writer.waitForTraces(1)

    then:
    spanFinished(span1)
    sortSpansByStart()
    writer == [[span1, span1A, span1A1, span1A2]]

    and:
    scopeManager.active() == null
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
