package datadog.trace.bootstrap.instrumentation.iast


import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable.Source
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.ContextStore
import datadog.trace.test.util.DDSpecification

class NamedContextTest extends DDSpecification {

  protected PropagationModule module
  protected ContextStore store

  void setup() {
    module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    store = Mock(ContextStore)
  }

  void 'test that the context taints names and values'() {
    given:
    final source = new SourceImpl(origin: SourceTypes.REQUEST_PARAMETER_NAME)
    final target = new Object()
    final name = 'name'
    final value = 'value'

    when:
    final context = NamedContext.getOrCreate(store, target)

    then:
    1 * store.get(target) >> null
    1 * module.findSource(target) >> source
    1 * store.put(target, _)

    when:
    context.taintName(name)

    then:
    1 * module.taintString(_, name, source.origin, name, source.value)

    when:
    context.taintName(name)

    then:
    0 * _

    when:
    context.taintValue(value)

    then:
    1 * module.taintString(_, value, source.origin, name, source.value)
    0 * _
  }

  void 'test context for non tainted'() {
    given:
    final target = 'test'

    when:
    final ctx = NamedContext.getOrCreate(store, target)

    then:
    1 * module.findSource(target) >> null
    1 * store.put(target, _)

    when:
    ctx.taintName('name')

    then:
    0 * _

    when:
    ctx.taintValue('value')

    then:
    0 * _
  }

  private static class SourceImpl implements Source {
    byte origin
    String name
    String value
  }
}
