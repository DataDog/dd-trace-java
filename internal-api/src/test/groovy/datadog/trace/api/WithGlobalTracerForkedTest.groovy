package datadog.trace.api


import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ConcurrentLinkedQueue

class WithGlobalTracerForkedTest extends DDSpecification {

  def "should call the callback at the right time"() {
    setup:
    def tracer = Mock(AgentTracer.TracerAPI)
    def queue = new ConcurrentLinkedQueue<Tracer>()

    def callback = new WithGlobalTracer.Callback() {
        @Override
        void withTracer(AgentTracer.TracerAPI t) {
          queue.add(t)
        }
      }

    when:
    WithGlobalTracer.registerOrExecute(callback)

    then:
    queue.isEmpty()

    when:
    WithGlobalTracer.registerOrExecute(callback)

    then:
    queue.isEmpty()

    when:
    GlobalTracer.registerIfAbsent(tracer)

    then:
    queue.toList() == [tracer, tracer]

    when:
    WithGlobalTracer.registerOrExecute(callback)

    then:
    queue.toList() == [tracer, tracer, tracer]
  }

  def "should not crash on unsupported tracer type"() {
    setup:
    def tracer = Mock(Tracer)
    def callback = new WithGlobalTracer.Callback() {
        @Override
        void withTracer(AgentTracer.TracerAPI t) {
        }
      }

    when:
    WithGlobalTracer.registerOrExecute(callback)
    GlobalTracer.registerIfAbsent(tracer)

    then:
    notThrown(ClassCastException)
  }
}
