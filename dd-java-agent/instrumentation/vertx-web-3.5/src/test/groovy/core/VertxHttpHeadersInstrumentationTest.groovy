package core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic
import io.vertx.core.MultiMap
import io.vertx.core.http.impl.headers.VertxHttpHeaders

@CompileDynamic
class VertxHttpHeadersInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test that get() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([key: 'value'], headers)

    when:
    headers.get('key')

    then:
    1 * module.taintIfTainted('value', headers, SourceTypes.REQUEST_HEADER_VALUE, 'key')
  }

  void 'test that getAll() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    headers.getAll('key')

    then:
    1 * module.isTainted(headers) >> { false }
    0 * _

    when:
    headers.getAll('key')

    then:
    1 * module.isTainted(headers) >> { true }
    1 * module.taint(_, 'value1', SourceTypes.REQUEST_HEADER_VALUE, 'key')
    1 * module.taint(_, 'value2', SourceTypes.REQUEST_HEADER_VALUE, 'key')
  }

  void 'test that names() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    headers.names()

    then:
    1 * module.isTainted(headers) >> { false }
    0 * _

    when:
    headers.names()

    then:
    1 * module.isTainted(headers) >> { true }
    1 * module.taint(_, 'key', SourceTypes.REQUEST_HEADER_NAME, 'key')
  }

  void 'test that entries() is instrumented'() {
    given:
    final headers = new VertxHttpHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    final result = headers.entries()

    then:
    module.isTainted(headers) >> { false }
    0 * _

    when:
    headers.entries()

    then:
    module.isTainted(headers) >> { true }
    result.collect { it.key }.unique().each {
      (1.._) * module.taint(_, it, SourceTypes.REQUEST_HEADER_NAME, it) // entries relies on names() on some impls
    }
    result.each {
      1 * module.taint(_, it.value, SourceTypes.REQUEST_HEADER_VALUE, it.key)
    }
  }

  private static void addAll(final Map<String, String> map, final MultiMap headers) {
    map.each { key, value -> headers.add(key, value) }
  }

  private static void addAll(final List<Map<String, String>> list, final MultiMap headers) {
    list.each { addAll(it, headers)  }
  }
}
