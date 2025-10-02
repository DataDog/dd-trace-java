import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import groovy.transform.CompileDynamic

import javax.servlet.http.Cookie

@CompileDynamic
class CookieInstrumentationTest extends InstrumentationSpecification {

  private static final String NAME = 'name'
  private static final String VALUE = 'value'

  private Object iastCtx

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  @Override
  void setup() {
    iastCtx = Stub(IastContext)
  }

  void 'test getName'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookie = new Cookie(NAME, VALUE)

    when:
    final result = runUnderIastTrace { cookie.getName() }

    then:
    result == NAME
    1 * iastModule.taintStringIfTainted(iastCtx, NAME, cookie, SourceTypes.REQUEST_COOKIE_NAME, NAME)
  }

  void 'test getValue'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookie = new Cookie(NAME, VALUE)

    when:
    final result = runUnderIastTrace { cookie.getValue() }

    then:
    result == VALUE
    1 * iastModule.taintStringIfTainted(iastCtx, VALUE, cookie, SourceTypes.REQUEST_COOKIE_VALUE, NAME)
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
