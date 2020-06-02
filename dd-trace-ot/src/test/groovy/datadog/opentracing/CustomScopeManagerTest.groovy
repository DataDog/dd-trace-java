package datadog.opentracing

import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.util.test.DDSpecification
import io.opentracing.Scope
import io.opentracing.ScopeManager
import io.opentracing.Span

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces

class CustomScopeManagerTest extends DDSpecification {
  def writer = new ListWriter()
  def scopeManager = new TestScopeManager()
  def tracer = DDTracer.builder().writer(writer).scopeManager(scopeManager).build()

  def "simple span works"() {
    when:
    Scope scope
    try {
      scope = tracer.buildSpan("someOperation")
        .withTag(DDTags.SERVICE_NAME, "someService")
        .startActive(true)
    } finally {
      scope.close()
    }

    then:
    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            "testScope" true
            defaultTags()
          }
        }
      }
    }
  }

  def "using scope manager directly"() {
    when:
    Span testSpan = tracer.buildSpan("someOperation")
      .withTag(DDTags.SERVICE_NAME, "someService")
      .start()

    Scope scope = tracer.scopeManager().activate(testSpan)
    scope.close()
    testSpan.finish()

    then:
    tracer.scopeManager() instanceof TestScopeManager
    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            "testScope" true
            defaultTags()
          }
        }
      }
    }
  }

  def "interactions from core"() {
    given:
    CoreTracer coreTracer = tracer.tracer

    when:
    Span testSpan = tracer.buildSpan("someOperation")
      .withTag(DDTags.SERVICE_NAME, "someService")
      .start()
    tracer.activateSpan(testSpan)

    then:
    coreTracer.activeSpan() != null
    !coreTracer.activeScope().isAsyncPropagating()

    when:
    coreTracer.activeScope().setAsyncPropagation(true)

    then:
    // regular scope not TraceScope from custom scope manager so this is always fals
    !coreTracer.activeScope().isAsyncPropagating()
    coreTracer.activeScope().capture() == null

    when:
    Scope scope = tracer.scopeManager().activate(testSpan)
    scope.close()
    testSpan.finish()

    then:
    tracer.scopeManager() instanceof TestScopeManager
    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            "testScope" true
            defaultTags()
          }
        }
      }
    }
  }

  def "TraceScopes interactions"() {
    when:
    scopeManager.returnTraceScopes = true
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
    continuation.close()
    scope.close()

    then:
    tracer.scopeManager() instanceof TestScopeManager
    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            "testScope" true
            defaultTags()
          }
        }
      }
    }
  }

  def "TraceScope interactions from CoreTracer side"() {
    given:
    CoreTracer coreTracer = tracer.tracer

    when:
    scopeManager.returnTraceScopes = true
    tracer.buildSpan("someOperation")
      .withTag(DDTags.SERVICE_NAME, "someService")
      .startActive(true)

    then:
    !coreTracer.activeScope().isAsyncPropagating()

    when:
    coreTracer.activeScope().setAsyncPropagation(true)
    TraceScope.Continuation continuation = coreTracer.activeScope().capture()

    then:
    coreTracer.activeScope().isAsyncPropagating()
    continuation != null

    when:
    continuation.cancel()
    coreTracer.activeScope().close()

    then:
    tracer.scopeManager() instanceof TestScopeManager
    assertTraces(writer, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            "testScope" true
            defaultTags()
          }
        }
      }
    }
  }
}

class TestScopeManager implements ScopeManager {
  boolean returnTraceScopes = false
  TestScope currentScope

  @Override
  Scope active() {
    return currentScope
  }

  @Override
  Span activeSpan() {
    return currentScope == null ? null : currentScope.span
  }

  @Override
  Scope activate(Span span) {
    return activate(span, false)
  }

  @Override
  Scope activate(Span span, boolean finishSpanOnClose) {
    if (returnTraceScopes) {
      currentScope = new TestTraceScope(currentScope, span, finishSpanOnClose)
    } else {
      currentScope = new TestScope(currentScope, span, finishSpanOnClose)
    }
    return currentScope
  }

  class TestScope implements Scope {
    final TestScope parent
    final Span span
    final boolean finishOnClose

    TestScope(TestScope parent, Span span, boolean finishOnClose) {
      this.parent = parent
      this.span = span
      this.finishOnClose = finishOnClose
    }

    @Override
    void close() {
      span.setTag("testScope", true) // Set a tag so we know the custom scope is used
      if (finishOnClose) {
        span.finish()
      }

      currentScope = parent
    }

    @Override
    Span span() {
      return span
    }
  }

  class TestTraceScope extends TestScope implements TraceScope {
    boolean asyncPropagating = false

    TestTraceScope(TestScope parent, Span span, boolean finishOnClose) {
      super(parent, span, finishOnClose)
    }

    @Override
    Continuation capture() {
      return new Continuation() {
        @Override
        TraceScope activate() {
          return TestTraceScope.this
        }

        @Override
        void cancel() {
        }
      }
    }

    @Override
    boolean isAsyncPropagating() {
      return asyncPropagating
    }

    @Override
    void setAsyncPropagation(boolean value) {
      asyncPropagating = true
    }
  }
}


