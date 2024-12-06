package core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.taint.TaintedObjects
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import groovy.transform.CompileDynamic
import io.vertx.core.MultiMap
import io.vertx.core.http.impl.headers.VertxHttpHeaders

@CompileDynamic
class VertxHttpHeadersInstrumentationTest extends AgentTestRunner {

  private Object iastCtx
  private Object to

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void setup() {
    to = Stub(TaintedObjects)
    iastCtx = Stub(IastContext) {
      getTaintedObjects() >> to
    }
  }

  void 'test that get() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([key: 'value'], headers)

    when:
    runUnderIastTrace { headers.get('key') }

    then:
    1 * module.taintObjectIfTainted(to, 'value', headers, SourceTypes.REQUEST_HEADER_VALUE, 'key')
  }

  void 'test that getAll() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    runUnderIastTrace { headers.getAll('key') }

    then:
    1 * module.isTainted(to, headers) >> { false }
    0 * _

    when:
    runUnderIastTrace { headers.getAll('key') }

    then:
    1 * module.isTainted(to, headers) >> { true }
    1 * module.taintObject(to, 'value1', SourceTypes.REQUEST_HEADER_VALUE, 'key')
    1 * module.taintObject(to, 'value2', SourceTypes.REQUEST_HEADER_VALUE, 'key')
  }

  void 'test that names() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    runUnderIastTrace { headers.names() }

    then:
    1 * module.isTainted(to, headers) >> { false }
    0 * _

    when:
    runUnderIastTrace { headers.names() }

    then:
    1 * module.isTainted(to, headers) >> { true }
    1 * module.taintObject(to, 'key', SourceTypes.REQUEST_HEADER_NAME, 'key')
  }

  void 'test that entries() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    final result = runUnderIastTrace { headers.entries() }

    then:
    module.isTainted(to, headers) >> { false }
    0 * _

    when:
    runUnderIastTrace { headers.entries() }

    then:
    module.isTainted(to, headers) >> { true }
    result.collect { it.key }.unique().each {
      (1.._) * module.taintObject(to, it, SourceTypes.REQUEST_HEADER_NAME, it) // entries relies on names() on some impls
    }
    result.each {
      1 * module.taintObject(to, it.value, SourceTypes.REQUEST_HEADER_VALUE, it.key)
    }
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

  private static void addAll(final Map<String, String> map, final MultiMap headers) {
    map.each { key, value -> headers.add(key, value) }
  }

  private static void addAll(final List<Map<String, String>> list, final MultiMap headers) {
    list.each { addAll(it, headers)  }
  }
}
