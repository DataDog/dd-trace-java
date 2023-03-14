import datadog.smoketest.controller.CookieTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.source.WebModule
import groovy.transform.CompileDynamic

@CompileDynamic
class CookieCallSiteTest extends AgentTestRunner {

  private static final String NAME = 'name'
  private static final String VALUE = 'value'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getName'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE)

    when:
    final result = cookieTestSuite.getName()

    then:
    result == NAME
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, NAME, SourceTypes.REQUEST_COOKIE_NAME)
  }

  void 'test getValue'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE)

    when:
    final result = cookieTestSuite.getValue()

    then:
    result == VALUE
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, VALUE, SourceTypes.REQUEST_COOKIE_VALUE)
  }
}
