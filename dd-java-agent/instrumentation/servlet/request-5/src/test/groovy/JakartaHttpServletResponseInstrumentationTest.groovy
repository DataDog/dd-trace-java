import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.InsecureCookieModule
import foo.bar.smoketest.DummyResponse
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper

class JakartaHttpServletResponseInstrumentationTest  extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'insecure cookie added using addCookie'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()
    final cookie = new Cookie("user-id", "7")

    when:
    response.addCookie(cookie)

    then:
    1 * module.onCookie("user-id", false)
    0 * _
  }

  void 'make sure we do not instrument subclasses of HttpServletResponseWrapper'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final request = Mock(HttpServletResponse)
    final wrapper = new HttpServletResponseWrapper(request)
    final cookie = new Cookie("user-id", "7")

    when:
    wrapper.addCookie(cookie)

    then:
    1 * request.addCookie(cookie)
    0 * _
  }

  void 'secure cookie added using addCookie'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()
    final cookie = new Cookie("user-id", "7")
    cookie.setSecure(true)

    when:
    response.addCookie(cookie)

    then:
    1 * module.onCookie('user-id', true)
    0 * _
  }

  void 'null cookie added using addCookie'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addCookie(null)

    then:
    0 * _
  }

  void 'insecure cookie added using addHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id=7")

    then:
    1 * module.onCookieHeader('user-id=7')
    0 * _
  }

  void 'null parameters added using addHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader(null, null)

    then:
    noExceptionThrown()
    0 * module.onCookieHeader(_)
    0 * _
  }

  void 'null value added using addHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", null)

    then:
    noExceptionThrown()
    0 * module.onCookieHeader(_)
    0 * _
  }

  void 'null header name added using addHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader(null, "user-id=7")

    then:
    noExceptionThrown()
    0 * module.onCookieHeader(_)
    0 * _
  }


  void 'secure cookie added using addHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id=7; Secure")

    then:
    1 * module.onCookieHeader('user-id=7; Secure')
    0 * _
  }

  void 'adding non cookie header'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader("Custom-Header", "user-id=7")

    then:
    0 * _
  }

  void 'cookie without name value pair'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id")

    then:
    1 * module.onCookieHeader('user-id')
    0 * _
  }


  void 'insecure cookie added using setHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7")

    then:
    1 * module.onCookieHeader('user-id=7')
    0 * _
  }

  void 'secure cookie added using setHeader'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7; Secure")

    then:
    1 * module.onCookieHeader('user-id=7; Secure')
    0 * _
  }

  void 'secure cookie added using setHeader without spaces'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7;Secure")

    then:
    1 * module.onCookieHeader('user-id=7;Secure')
    0 * _
  }
}
