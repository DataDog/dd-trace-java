package datadog.trace.bootstrap.instrumentation.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable.Source
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.ContextStore
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class NamedContextTest extends DDSpecification {

  @Shared
  protected static final TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  protected PropagationModule module
  protected ContextStore store
  protected TracerAPI tracer
  protected IastContext ctx

  void setup() {
    ctx = Stub(IastContext)
    final reqCtx = Stub(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Stub(AgentSpan) {
      getRequestContext() >> reqCtx
    }
    tracer = Stub(TracerAPI) {
      activeSpan() >> span
    }
    AgentTracer.forceRegister(tracer)
    module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    store = Mock(ContextStore)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
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
    1 * module.findSource(ctx, target) >> source
    1 * store.put(target, _)

    when:
    context.taintName(name)

    then:
    1 * module.taint(ctx, name, source.origin, name, source.value)

    when:
    context.taintName(name)

    then:
    0 * _

    when:
    context.taintValue(value)

    then:
    1 * module.taint(ctx, value, source.origin, name, source.value)
    0 * _
  }

  void 'test context for non tainted'() {
    given:
    final target = 'test'

    when:
    final ctx = NamedContext.getOrCreate(store, target)

    then:
    1 * module.findSource(ctx, target) >> null
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
