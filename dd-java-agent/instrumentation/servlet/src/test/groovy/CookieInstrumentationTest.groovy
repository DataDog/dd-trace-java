import datadog.smoketest.controller.CookieTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic

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
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE)

    when:
    final result = cookieTestSuite.getName()

    then:
    result == NAME
    1 * iastModule.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, NAME, NAME, cookieTestSuite.getCookie())
  }

  void 'test getValue'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE)

    when:
    final result = cookieTestSuite.getValue()

    then:
    result == VALUE
    1 * iastModule.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_VALUE, NAME, VALUE, cookieTestSuite.getCookie())
  }
}
