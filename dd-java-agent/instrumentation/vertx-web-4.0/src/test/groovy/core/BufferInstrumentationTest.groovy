package core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.taint.Source
import datadog.trace.api.iast.taint.TaintedObjects
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import groovy.transform.CompileDynamic
import io.vertx.core.buffer.Buffer
import io.vertx.core.buffer.impl.BufferImpl

@CompileDynamic
class BufferInstrumentationTest extends AgentTestRunner {

  private Object iastCtx
  private Object to

  void setup() {
    to = Stub(TaintedObjects)
    iastCtx = Stub(IastContext) {
      getTaintedObjects() >> to
    }
  }

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test that Buffer.#methodName is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final buffer = taintedInstance(SourceTypes.REQUEST_BODY)

    when:
    runUnderIastTrace {
      method.call(buffer)
    }

    then:
    1 * module.taintObjectIfTainted(to, _, buffer)

    where:
    methodName         | method
    'toString()'       | { Buffer b -> b.toString() }
    'toString(String)' | { Buffer b -> b.toString('UTF-8') }
  }

  void 'test that Buffer.#methodName is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final buffer = new BufferImpl()
    final tainted = taintedInstance(SourceTypes.REQUEST_BODY)

    when:
    runUnderIastTrace {
      method.call(buffer, tainted)
    }

    then:
    1 * module.taintObjectIfTainted(to, buffer, tainted)

    where:
    methodName                       | method
    'appendBuffer(Buffer)'           | { Buffer b, Buffer taint -> b.appendBuffer(taint) }
    'appendBuffer(buffer, int, int)' | { Buffer b, Buffer taint -> b.appendBuffer(taint, 0, taint.length()) }
  }

  private Buffer taintedInstance(final byte origin) {
    final buffer = new BufferImpl('Hello World!')
    if (buffer instanceof Taintable) {
      final source = Mock(Source) {
        getOrigin() >> origin
      }
      (buffer as Taintable).$$DD$setSource(source)
    }
    return buffer
  }

  protected <E> E runUnderIastTrace(Closure<E> cl) {
    final ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    final span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      return AgentTracer.activateSpan(span).withCloseable(cl)
    } finally {
      span.finish()
    }
  }
}
