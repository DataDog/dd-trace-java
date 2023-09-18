import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.HttpResponseHeaderModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.util.Cookie as IastCookie
import foo.bar.DummyResponse

import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponseWrapper

class HttpServletResponseInstrumentationTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'insecure cookie added using addCookie'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()
    final cookie = new Cookie("user-id", "7")

    when:
    response.addCookie(cookie)

    then:
    1 * module.onCookie({ IastCookie vul -> vul.cookieName == cookie.name && vul.secure == cookie.secure })
    0 * _
  }

  void 'make sure we do not instrument subclasses of HttpServletResponseWrapper'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final request = Mock(HttpServletResponse)
    final wrapper = new HttpServletResponseWrapper(request)
    final cookie = new Cookie("user-id", "7")

    when:
    wrapper.addCookie(cookie)

    then:
    1 * request.addCookie(_)
    0 * _
  }

  void 'secure cookie added using addCookie'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()
    final cookie = new Cookie("user-id", "7")
    cookie.setSecure(true)

    when:
    response.addCookie(cookie)

    then:
    1 * module.onCookie({ IastCookie vul -> vul.cookieName == cookie.name && vul.secure == cookie.secure })
    0 * _
  }

  void 'null cookie added using addCookie'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addCookie((Cookie) null)

    then:
    0 * _
  }

  void 'insecure cookie added using addHeader'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addHeader("Set-Cookie", "user-id=7")

    then:
    1 * module.onHeader('Set-Cookie', 'user-id=7')
    0 * _
  }


  void 'insecure cookie added using setHeader'() {
    setup:
    final cookieModule = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(cookieModule)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7")

    then:
    1 * cookieModule.onHeader('Set-Cookie', 'user-id=7')
    0 * _
  }

  void 'unvalidated redirect checked using addHeader'() {
    setup:
    final redirectModule = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.addHeader("Location", "http://dummy.url.com")

    then:
    1 * redirectModule.onHeader('Location', 'http://dummy.url.com')
    0 * _
  }


  void 'unvalidated redirect checked setHeader'() {
    setup:
    final redirectModule = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final response = new DummyResponse()

    when:
    response.setHeader("Location", "http://dummy.url.com")

    then:
    1 * redirectModule.onHeader('Location', 'http://dummy.url.com')
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

  void 'test instrumentation with unknown types'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.addCookie(new DummyResponse.CustomCookie())

    then:
    0 * _

    when:
    response.addHeader(new DummyResponse.CustomHeaderName(), "value")

    then:
    0 * _

    when:
    response.setHeader(new DummyResponse.CustomHeaderName(), "value")

    then:
    0 * _
  }
}
