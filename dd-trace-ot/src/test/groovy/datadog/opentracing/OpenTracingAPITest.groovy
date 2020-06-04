package datadog.opentracing

import datadog.trace.api.DDTags
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.api.interceptor.TraceInterceptor
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.ScopeListener
import datadog.trace.context.TraceScope
import datadog.trace.util.test.DDSpecification
import io.opentracing.Scope
import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces

class OpenTracingAPITest extends DDSpecification {
  def writer = new ListWriter()

  def tracer = DDTracer.builder().writer(writer).build()

  def traceInterceptor = Mock(TraceInterceptor)
  def scopeListener = Mock(ScopeListener)

  def setup() {
    assert tracer.scopeManager().active() == null
    tracer.addTraceInterceptor(traceInterceptor)
    tracer.addScopeListener(scopeListener)
  }

  def "tracer/scopeManager returns null for no active span"() {
    expect:
    tracer.activeSpan() == null
    tracer.scopeManager().active() == null
    tracer.scopeManager().activeSpan() == null
  }

  def "single span"() {
    when:
    Scope scope
    try {
      scope = tracer.buildSpan("someOperation").startActive(true)
      scope.span().setTag(DDTags.SERVICE_NAME, "someService")
    } finally {
      scope.close()
    }

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }

    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "span with builder"() {
    when:
    Span testSpan = tracer.buildSpan("someOperation")
      .withTag(Tags.COMPONENT, "test-component")
      .withTag("someBoolean", true)
      .withTag("someNumber", 1)
      .withTag(DDTags.SERVICE_NAME, "someService")
      .start()

    Scope scope
    try {
      scope = tracer.activateSpan(testSpan)
      testSpan.finish()
    } finally {
      scope.close()
    }

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }
    testSpan instanceof MutableSpan

    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            "$datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT" "test-component"
            "someBoolean" true
            "someNumber" 1
            defaultTags()
          }
        }
      }
    }
  }

  def "single span with manual start/finish"() {
    when:
    Span testSpan = tracer.buildSpan("someOperation").start()
    Scope scope = tracer.activateSpan(testSpan)

    then:
    1 * scopeListener.afterScopeActivated()
    testSpan instanceof MutableSpan
    scope.span() instanceof MutableSpan

    when:
    testSpan.setTag(DDTags.SERVICE_NAME, "someService")
    testSpan.setTag(Tags.COMPONENT, "test-component")
    testSpan.setTag("someBoolean", true)
    testSpan.setTag("someNumber", 1)
    testSpan.setOperationName("someOtherOperation")
    scope.close()
    testSpan.finish()

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }
    1 * scopeListener.afterScopeClosed()

    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOtherOperation"
          resourceName "someOtherOperation"
          tags {
            "$datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT" "test-component"
            "someBoolean" true
            "someNumber" 1
            defaultTags()
          }
        }
      }
    }
  }

  def "spans and scopes all equal"() {
    when:
    Span testSpan = tracer.buildSpan("someOperation").start()
    Scope testScope = tracer.activateSpan(testSpan)

    Span traceActiveSpan = tracer.activeSpan()
    Span scopeManagerActiveSpan = tracer.scopeManager().activeSpan()
    Span scopeActiveSpan = testScope.span()

    Scope scopeManagerActiveScope = tracer.scopeManager().active()
    testScope.close()
    testSpan.finish()

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }
    testSpan == traceActiveSpan
    testSpan.hashCode() == traceActiveSpan.hashCode()
    testSpan == scopeManagerActiveSpan
    testSpan.hashCode() == scopeManagerActiveSpan.hashCode()
    testSpan == scopeActiveSpan
    testSpan.hashCode() == scopeActiveSpan.hashCode()
    testScope == scopeManagerActiveScope
    testScope.hashCode() == scopeManagerActiveScope.hashCode()
  }

  def "nested spans"() {
    when:
    Scope scope
    try {
      scope = tracer.buildSpan("someOperation").startActive(true)
      scope.span().setTag(DDTags.SERVICE_NAME, "someService")

      Scope scope2
      try {
        scope2 = tracer.buildSpan("someOperation2").startActive(true)
      } finally {
        scope2.close()
      }
    } finally {
      scope.close()
    }

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 2 }) >> { args -> args[0] }

    assertTraces(writer, 1) {
      trace(0, 2) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            defaultTags()
          }
        }
        span(1) {
          serviceName "someService"
          operationName "someOperation2"
          resourceName "someOperation2"
          childOf(span(0))
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "span with async propagation"() {
    when:
    Scope scope = tracer.buildSpan("someOperation")
      .withTag(DDTags.SERVICE_NAME, "someService")
      .startActive(true)

    then:
    scope instanceof TraceScope
    !((TraceScope) scope).isAsyncPropagating()

    when:
    ((TraceScope) scope).setAsyncPropagation(true)
    TraceScope.Continuation continuation = ((TraceScope) scope).capture()

    then:
    ((TraceScope) scope).isAsyncPropagating()
    continuation != null

    when:
    continuation.cancel()
    scope.close()

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }

    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "SpanContext ids equal tracer ids"() {
    when:
    Span testSpan = tracer.buildSpan("someOperation")
      .withServiceName("someService")
      .start()
    Scope scope = tracer.activateSpan(testSpan)

    then:
    1 * scopeListener.afterScopeActivated()

    testSpan.context().toSpanId() == tracer.getSpanId()
    testSpan.context().toTraceId() == tracer.getTraceId()
    testSpan.context().toSpanId() == tracer.tracer.getSpanId()
    testSpan.context().toTraceId() == tracer.tracer.getTraceId()

    when:
    scope.close()
    testSpan.finish()

    then:
    1 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }
    1 * scopeListener.afterScopeClosed()

    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "inject and extract context"() {
    given:
    def textMap = new TextMapAdapter(new HashMap<String, String>())

    when:
    Span testSpan = tracer.buildSpan("clientOperation")
      .withServiceName("someClientService")
      .start()
    Scope scope = tracer.activateSpan(testSpan)

    tracer.inject(testSpan.context(), Format.Builtin.HTTP_HEADERS, textMap)


    SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS, textMap)
    Span serverSpan = tracer.buildSpan("serverOperation")
      .withServiceName("someService")
      .asChildOf(extractedContext)
      .start()
    tracer.activateSpan(serverSpan).close()
    serverSpan.finish()

    scope.close()
    testSpan.finish()

    then:
    2 * traceInterceptor.onTraceComplete({ it.size() == 1 }) >> { args -> args[0] }
    extractedContext.toTraceId() == testSpan.context().toTraceId()
    extractedContext.toSpanId() == testSpan.context().toSpanId()

    assertTraces(writer, 2) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "serverOperation"
          resourceName "serverOperation"
          childOf(trace(1).get(0))
          tags {
            defaultTags(true)
          }
        }
      }
      trace(1, 1) {
        span(0) {
          serviceName "someClientService"
          operationName "clientOperation"
          resourceName "clientOperation"
          tags {
            defaultTags()
          }
        }
      }
    }
  }
}
