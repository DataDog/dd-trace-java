import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext

class JWTParserInstrumentationTest extends InstrumentationSpecification {

  private Object iastCtx

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void setup() {
    iastCtx = Stub(IastContext)
  }

  void 'oauth jwt parser'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final String payload = "{}"

    when:
    runUnderIastTrace { new com.auth0.jwt.impl.JWTParser().parsePayload(payload) }

    then:
    1 * propagationModule.taintString(iastCtx, payload, SourceTypes.REQUEST_HEADER_VALUE)
    0 * _
  }

  void 'jose JSONObjectUtils instrumentation'() {
    setup:
    def json = "{\"iss\":\"http://foobar.com\",\"sub\":\"foo\",\"aud\":\"foobar\",\"name\":\"Mr Foo Bar\",\"scope\":\"read\",\"iat\":1516239022,\"exp\":2500000000}"
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    when:
    runUnderIastTrace { com.nimbusds.jose.util.JSONObjectUtils.parse(json) }

    then:
    1 * propagationModule.taintString(iastCtx, 'http://foobar.com', SourceTypes.REQUEST_HEADER_VALUE, 'iss')
    1 * propagationModule.taintString(iastCtx, 'foo', SourceTypes.REQUEST_HEADER_VALUE, 'sub')
    1 * propagationModule.taintString(iastCtx, 'foobar', SourceTypes.REQUEST_HEADER_VALUE, 'aud')
    1 * propagationModule.taintString(iastCtx, 'Mr Foo Bar', SourceTypes.REQUEST_HEADER_VALUE, 'name')
    1 * propagationModule.taintString(iastCtx, 'read', SourceTypes.REQUEST_HEADER_VALUE, 'scope')
    0 * _
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
