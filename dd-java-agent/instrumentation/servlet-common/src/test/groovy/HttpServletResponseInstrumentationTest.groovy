import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.InsecureCookieModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import foo.bar.DummyResponse

import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponseWrapper

class HttpServletResponseInstrumentationTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'insecure cookie added using addCookie'() {
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

  void 'make sure we do not instrument subclasses of HttpServletResponseWrapper'() {
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

  void 'secure cookie added using addCookie'() {
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

  void 'null cookie added using addCookie'() {
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addCookie(null)

    then:
    0 * _
  }

  void 'insecure cookie added using addHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id=7")

    then:
    1 * cookieModule.onCookieHeader('user-id=7')
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'unvalidated redirect added using addHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Location", "http://dummy.location.com")

    then:
    1 * redirectModule.onRedirect('http://dummy.location.com')
    0 * cookieModule.onCookieHeader(_)
    0 * _
  }

  void 'null parameters added using addHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader(null, null)

    then:
    noExceptionThrown()
    0 * cookieModule.onCookieHeader(_)
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'null value added using addHeader to set cookies'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", null)

    then:
    noExceptionThrown()
    0 * cookieModule.onCookieHeader(_)
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'null value added using addHeader to set location'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Location", null)

    then:
    noExceptionThrown()
    0 * cookieModule.onCookieHeader(_)
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'null header name added using addHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader(null, "user-id=7")

    then:
    noExceptionThrown()
    0 * cookieModule.onCookieHeader(_)
    0 * redirectModule.onRedirect(_)
    0 * _
  }


  void 'secure cookie added using addHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id=7; Secure")

    then:
    1 * cookieModule.onCookieHeader('user-id=7; Secure')
    0 * redirectModule.onRedirect(_)
    0 * _
  }


  void 'adding non cookie header'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Custom-Header", "user-id=7")

    then:
    0 * cookieModule.onCookieHeader(_)
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'cookie without name value pair'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id")

    then:
    1 * cookieModule.onCookieHeader('user-id')
    0 * redirectModule.onRedirect(_)
    0 * _
  }


  void 'insecure cookie added using setHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7")

    then:
    1 * cookieModule.onCookieHeader('user-id=7')
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'secure cookie added using setHeader'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7; Secure")

    then:
    1 * cookieModule.onCookieHeader('user-id=7; Secure')
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'secure cookie added using setHeader without spaces'() {
    setup:
    final cookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7;Secure")

    then:
    1 * cookieModule.onCookieHeader('user-id=7;Secure')
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'redirection added using sendRedirect'() {
    setup:
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.sendRedirect("http://dummy.location.com")

    then:
    1 * redirectModule.onRedirect('http://dummy.location.com')
    0 * _
  }

  void 'null location added using sendRedirect'() {
    setup:
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.sendRedirect(null)

    then:
    noExceptionThrown()
    0 * redirectModule.onRedirect(_)
    0 * _
  }

  void 'taint encoded url using encodeRedirectURL'() {
    setup:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.encodeRedirectURL("http://dummy.url.com")

    then:
    noExceptionThrown()
    1 * module.taintIfInputIsTainted(_, "http://dummy.url.com")
    0 * _
  }

  void 'taint encoded url using encodeURL'() {
    setup:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.encodeURL("http://dummy.url.com")

    then:
    noExceptionThrown()
    1 * module.taintIfInputIsTainted(_, "http://dummy.url.com")
    0 * _
  }
}
