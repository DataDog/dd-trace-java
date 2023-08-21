package core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic
import io.vertx.core.MultiMap
import io.vertx.core.http.CaseInsensitiveHeaders

@CompileDynamic
class CaseInsensitiveHeadersInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test that get() is instrumented'() {
    given:
    final headers = new CaseInsensitiveHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([key: 'value'], headers)

    when:
    headers.get('key')

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, 'key', 'value', headers)
  }

  void 'test that getAll() is instrumented'() {
    given:
    final headers = new CaseInsensitiveHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    headers.getAll('key')

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, 'key', ['value1', 'value2'], headers)
  }

  void 'test that names() is instrumented'() {
    given:
    final headers = new CaseInsensitiveHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    headers.names()

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_NAME, _, headers) >> {
      final names = it[1] as List<String>
      assert names == ['key']
    }
  }

  void 'test that entries() is instrumented'() {
    given:
    final headers = new CaseInsensitiveHeaders()
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    headers.entries()

    then:
    // latest versions of vertx 3.x define the entries in the MultiMap interface, so we will lose propagation
    if (hasMethod(CaseInsensitiveHeaders, 'entries')) {
      1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, _, headers)
    }
  }

  private static boolean hasMethod(final Class<?> target, final String name, final Class<?>...types) {
    try {
      target.getDeclaredMethod(name, types) != null
    } catch (Throwable e) {
      return false
    }
  }

  private static void addAll(final Map<String, String> map, final MultiMap headers) {
    map.each { key, value -> headers.add(key, value) }
  }

  private static void addAll(final List<Map<String, String>> list, final MultiMap headers) {
    list.each { addAll(it, headers)  }
  }
}
