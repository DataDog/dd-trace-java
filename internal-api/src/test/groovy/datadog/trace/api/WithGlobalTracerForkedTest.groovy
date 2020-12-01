package datadog.trace.api

import datadog.trace.api.interceptor.TraceInterceptor
import datadog.trace.context.ScopeListener
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ConcurrentLinkedQueue

class WithGlobalTracerForkedTest extends DDSpecification {
  static final QUEUE = new ConcurrentLinkedQueue<Tracer>()

  static final CALLBACK = new WithGlobalTracer.Callback() {
    @Override
    void withTracer(Tracer tracer) {
      QUEUE.add(tracer)
    }
  }

  static final TRACER = new Tracer() {
    @Override
    String getTraceId() {
      return null
    }

    @Override
    String getSpanId() {
      return null
    }

    @Override
    boolean addTraceInterceptor(TraceInterceptor traceInterceptor) {
      return false
    }

    @Override
    void addScopeListener(ScopeListener listener) {
    }
  }

  def "should call the callback at the right time"() {
    when:
    WithGlobalTracer.registerOrExecute(CALLBACK)

    then:
    QUEUE.isEmpty()

    when:
    WithGlobalTracer.registerOrExecute(CALLBACK)

    then:
    QUEUE.isEmpty()

    when:
    GlobalTracer.registerIfAbsent(TRACER)

    then:
    QUEUE.toList() == [TRACER, TRACER]

    when:
    WithGlobalTracer.registerOrExecute(CALLBACK)

    then:
    QUEUE.toList() == [TRACER, TRACER, TRACER]
  }
}
