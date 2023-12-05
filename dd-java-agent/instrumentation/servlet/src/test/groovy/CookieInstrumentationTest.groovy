import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic

import javax.servlet.http.Cookie

@CompileDynamic
class CookieInstrumentationTest extends AgentTestRunner {

  private static final String NAME = 'name'
  private static final String VALUE = 'value'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getName'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookie = new Cookie(NAME, VALUE)

    when:
    final result = cookie.getName()

    then:
    result == NAME
    1 * iastModule.taintIfTainted(NAME, cookie, SourceTypes.REQUEST_COOKIE_NAME, NAME)
  }

  void 'test getValue'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookie = new Cookie(NAME, VALUE)

    when:
    final result = cookie.getValue()

    then:
    result == VALUE
    1 * iastModule.taintIfTainted(VALUE, cookie, SourceTypes.REQUEST_COOKIE_VALUE, NAME)
  }
}
