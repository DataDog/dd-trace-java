import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule

class JWTParserInstrumentationTest  extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'oauth jwt parser'(){
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final String payload = "{}"

    when:
    new com.auth0.jwt.impl.JWTParser().parsePayload(payload)

    then:
    1 * propagationModule.taint(payload, SourceTypes.REQUEST_HEADER_VALUE)
    0 * _
  }

  void 'jose JSONObjectUtils instrumentation'() {
    setup:
    def json = "{\"iss\":\"http://foobar.com\",\"sub\":\"foo\",\"aud\":\"foobar\",\"name\":\"Mr Foo Bar\",\"scope\":\"read\",\"iat\":1516239022,\"exp\":2500000000}"
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    when:
    com.nimbusds.jose.util.JSONObjectUtils.parse(json)

    then:
    1 * propagationModule.taint('http://foobar.com', SourceTypes.REQUEST_HEADER_VALUE, 'iss')
    1 * propagationModule.taint('foo', SourceTypes.REQUEST_HEADER_VALUE, 'sub')
    1 * propagationModule.taint('foobar', SourceTypes.REQUEST_HEADER_VALUE, 'aud')
    1 * propagationModule.taint('Mr Foo Bar', SourceTypes.REQUEST_HEADER_VALUE, 'name')
    1 * propagationModule.taint('read', SourceTypes.REQUEST_HEADER_VALUE, 'scope')
    0 * _
  }
}
