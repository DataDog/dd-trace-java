import datadog.smoketest.controller.CookieTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule

class CookieCallSiteTest extends AgentTestRunner {

  private static final String NAME = 'name'
  private static final String VALUE = 'value'
  private static final String COMMENT = 'comment'
  private static final String DOMAIN = 'domain'
  private static final String PATH = 'path'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getName'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE, COMMENT, DOMAIN, PATH)

    when:
    final result = cookieTestSuite.getName()

    then:
    result == NAME
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, NAME, ((byte) 5).byteValue())
  }

  def 'test getValue'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE, COMMENT, DOMAIN, PATH)

    when:
    final result = cookieTestSuite.getValue()

    then:
    result == VALUE
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, VALUE, ((byte) 6).byteValue())
  }

  def 'test getComment'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE, COMMENT, DOMAIN, PATH)

    when:
    final result = cookieTestSuite.getComment()

    then:
    result == COMMENT
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, COMMENT, ((byte) 7).byteValue())
  }

  def 'test getDomain'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE, COMMENT, DOMAIN, PATH)

    when:
    final result = cookieTestSuite.getDomain()

    then:
    result == DOMAIN
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, DOMAIN, ((byte) 8).byteValue())
  }

  def 'test getPath'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookieTestSuite = new CookieTestSuite(NAME, VALUE, COMMENT, DOMAIN, PATH)

    when:
    final result = cookieTestSuite.getPath()

    then:
    result == PATH
    1 * iastModule.onCookieGetter(cookieTestSuite.getCookie(), NAME, PATH, ((byte) 9).byteValue())
  }
}
