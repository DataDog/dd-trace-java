package datadog.trace.agent.tooling.iast


import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class TaintableEnumerationTest extends DDSpecification {

  @Shared
  protected IastContext iastCtx = Stub(IastContext)

  protected PropagationModule module

  void setup() {
    module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
  }

  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'underlying enumerated values are tainted with a name'() {
    given:
    final values = (1..10).collect { "value$it".toString() }
    final origin = SourceTypes.REQUEST_PARAMETER_NAME
    final name = 'test'
    final enumeration = TaintableEnumeration.wrap(iastCtx, Collections.enumeration(values), module, origin, name)

    when:
    final result = enumeration.collect()

    then:
    result == values
    values.each { 1 * module.taintString(iastCtx, it, origin, name) }
  }

  void 'underlying enumerated values are tainted with the value as a name'() {
    given:
    final values = (1..10).collect { "value$it".toString() }
    final origin = SourceTypes.REQUEST_PARAMETER_NAME
    final enumeration = TaintableEnumeration.wrap(iastCtx, Collections.enumeration(values), module, origin, true)

    when:
    final result = enumeration.collect()

    then:
    result == values
    values.each { 1 * module.taintString(iastCtx, it, origin, it) }
  }

  void 'taintable enumeration leaves no trace in case of error'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_NAME
    final enumeration = TaintableEnumeration.wrap(iastCtx, new BadEnumeration(), module, origin, true)

    when:
    enumeration.hasMoreElements()

    then:
    final first = thrown(Error)
    first.stackTrace.find { it.className == TaintableEnumeration.name } == null
  }

  private static class BadEnumeration implements Enumeration<String> {
    @Override
    boolean hasMoreElements() {
      throw new Error('Ooops!!!')
    }

    @Override
    String nextElement() {
      throw new Error('Boom!!!')
    }
  }
}
