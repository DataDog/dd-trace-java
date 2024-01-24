package datadog.trace.api.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class IastContextTest extends DDSpecification {

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

  void setup() {
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  void 'test get context'() {
    when:
    final context = IastContext.Provider.get()

    then:
    1 * tracer.activeSpan() >> span
    context == iastCtx

    when:
    final nullContext = IastContext.Provider.get()

    then:
    1 * tracer.activeSpan() >> null
    nullContext == null
  }


  void 'test get context with span'() {
    when:
    final context = IastContext.Provider.get(span)

    then:
    context == iastCtx
  }

  void 'test get context with request context'() {
    when:
    final context = IastContext.Provider.get(reqCtx)

    then:
    context == iastCtx
  }

  void 'test get context with provider instance'() {
    given:
    final provider = Mock(IastContext.Provider)
    IastContext.Provider.register(provider)

    when:
    IastContext.Provider.get()

    then:
    1 * provider.resolve() >> null
  }
}
