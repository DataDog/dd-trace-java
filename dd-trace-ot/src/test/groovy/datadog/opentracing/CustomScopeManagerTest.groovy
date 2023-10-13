package datadog.opentracing

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import io.opentracing.Scope
import io.opentracing.ScopeManager
import io.opentracing.Span

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces

class CustomScopeManagerTest extends DDSpecification {
  def writer = new ListWriter()
  def scopeManager = new TestScopeManager()
  def tracer = DDTracer.builder().writer(writer).scopeManager(scopeManager).build()

  def cleanup() {
    tracer?.close()
  }

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
      trace(1) {
        span {
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
      trace(1) {
        span {
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
    coreTracer.activateSpan(((OTSpan) testSpan).getDelegate())

    then:
    coreTracer.activeSpan() != null
    def agentScope = coreTracer.activeScope()
    coreTracer.activeScope().equals(agentScope)
    coreTracer.activeScope().hashCode() == agentScope.hashCode()
    coreTracer.activeSpan().equals(agentScope.span())
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
      trace(1) {
        span {
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
    TraceScope.Continuation continuation = concurrent ?
      ((TraceScope) scope).captureConcurrent() : ((TraceScope) scope).capture()

    then:
    ((TraceScope) scope).isAsyncPropagating()
    continuation != null

    when:
    continuation.close()
    scope.close()

    then:
    tracer.scopeManager() instanceof TestScopeManager
    assertTraces(writer, 1) {
      trace(1) {
        span {
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

    where:
    concurrent << [false, true]
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
    def agentScope = coreTracer.activeScope()
    coreTracer.activeScope().equals(agentScope)
    coreTracer.activeScope().hashCode() == agentScope.hashCode()
    coreTracer.activeSpan().equals(agentScope.span())
    !coreTracer.activeScope().isAsyncPropagating()

    when:
    coreTracer.activeScope().setAsyncPropagation(true)
    TraceScope.Continuation continuation = concurrent ?
      coreTracer.activeScope().captureConcurrent() : coreTracer.activeScope().capture()

    then:
    coreTracer.activeScope().isAsyncPropagating()
    continuation != null

    when:
    continuation.cancel()
    coreTracer.activeScope().close()

    then:
    tracer.scopeManager() instanceof TestScopeManager
    assertTraces(writer, 1) {
      trace(1) {
        span {
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

    where:
    concurrent << [false, true]
  }
}

class TestScopeManager implements ScopeManager {
  def converter = new TypeConverter(new DefaultLogHandler())
  boolean returnTraceScopes = false
  Scope currentScope

  @Override
  Scope active() {
    return currentScope
  }

  @Override
  Span activeSpan() {
    return currentScope == null ? null : currentScope.span()
  }

  @Override
  Scope activate(Span span) {
    return activate(span, false)
  }

  @Override
  Scope activate(Span span, boolean finishSpanOnClose) {
    if (returnTraceScopes) {
      currentScope = new OTScopeManager.OTScope(
        new TestAgentScope(currentScope, span), finishSpanOnClose, converter)
    } else {
      currentScope = new TestScope(currentScope, span, finishSpanOnClose)
    }
    return currentScope
  }

  class TestScope implements Scope {
    final Scope parent
    final Span span
    final boolean finishOnClose

    TestScope(Scope parent, Span span, boolean finishOnClose) {
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

  class TestAgentScope implements AgentScope {
    final Scope parent
    final AgentSpan agentSpan

    boolean asyncPropagating = false

    TestAgentScope(Scope parent, Span span) {
      this.parent = parent
      this.agentSpan = new Object() {
          void setTag(String key, boolean value) {
            span.setTag(key, value)
          }
          void finish() {
            span.finish()
          }
        } as AgentSpan
    }

    @Override
    AgentSpan span() {
      return agentSpan
    }

    @Override
    byte source() {
      return ScopeSource.MANUAL.id()
    }

    @Override
    Continuation capture() {
      return new Continuation() {
          @Override
          AgentScope activate() {
            return TestAgentScope.this
          }

          @Override
          void cancel() {
          }

          @Override
          AgentSpan getSpan() {
            return TestAgentScope.this.span()
          }
        }
    }

    @Override
    Continuation captureConcurrent() {
      return capture()
    }

    @Override
    boolean isAsyncPropagating() {
      return asyncPropagating
    }

    @Override
    void setAsyncPropagation(boolean value) {
      asyncPropagating = value
    }

    @Override
    void close() {
      agentSpan.setTag("testScope", true) // Set a tag so we know the custom scope is used
      currentScope = parent
    }
  }
}


