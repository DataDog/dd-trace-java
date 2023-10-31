package datadog.trace.agent.tooling.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class TaintableEnumerationTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)

  protected IastContext iastCtx = Mock(IastContext)

  protected RequestContext reqCtx = Mock(RequestContext) {
    getData(RequestContextSlot.IAST) >> iastCtx
  }

  protected AgentSpan span = Mock(AgentSpan) {
    getRequestContext() >> reqCtx
  }

  protected PropagationModule module


  void setup() {
    AgentTracer.forceRegister(tracer)
    module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
    InstrumentationBridge.clearIastModules()
  }

  void 'underlying enumerated values are tainted with a name'() {
    given:
    final values = (1..10).collect { "value$it".toString() }
    final origin = SourceTypes.REQUEST_PARAMETER_NAME
    final name = 'test'
    final enumeration = TaintableEnumeration.wrap(Collections.enumeration(values), module, origin, name)

    when:
    final result = enumeration.collect()

    then:
    result == values
    values.each { 1 * module.taint(_, it, origin, name) }
    1 * tracer.activeSpan() >> span // only one access to the active context
  }

  void 'underlying enumerated values are tainted with the value as a name'() {
    given:
    final values = (1..10).collect { "value$it".toString() }
    final origin = SourceTypes.REQUEST_PARAMETER_NAME
    final enumeration = TaintableEnumeration.wrap(Collections.enumeration(values), module, origin, true)

    when:
    final result = enumeration.collect()

    then:
    result == values
    values.each { 1 * module.taint(_, it, origin, it) }
  }

  void 'taintable enumeration leaves no trace in case of error'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_NAME
    final enumeration = TaintableEnumeration.wrap(new BadEnumeration(), module, origin, true)

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
