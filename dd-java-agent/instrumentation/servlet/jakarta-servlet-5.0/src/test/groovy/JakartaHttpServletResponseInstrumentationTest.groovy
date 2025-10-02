import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.HttpResponseHeaderModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.util.Cookie as IastCookie
import foo.bar.smoketest.DummyResponse
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper

class JakartaHttpServletResponseInstrumentationTest extends InstrumentationSpecification {
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
    cookie.setMaxAge(3)

    when:
    response.addCookie(cookie)

    then:
    1 * module.onCookie({ IastCookie vul ->
      vul.cookieName == cookie.name &&
        vul.cookieValue == cookie.value &&
        vul.secure == cookie.secure &&
        vul.httpOnly == cookie.httpOnly &&
        vul.maxAge == cookie.maxAge
    })
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
    1 * request.addCookie(cookie)
    0 * _
  }

  void 'secure cookie added using addCookie'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()
    final cookie = new Cookie("user-id", "7")
    cookie.setSecure(true)
    cookie.setMaxAge(3)

    when:
    response.addCookie(cookie)

    then:
    1 * module.onCookie({ IastCookie vul ->
      vul.cookieName == cookie.name &&
        vul.cookieValue == cookie.value &&
        vul.secure == cookie.secure &&
        vul.httpOnly == cookie.httpOnly &&
        vul.maxAge == cookie.maxAge
    })
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

  void 'null parameters added using addHeader'() {
    setup:
    InstrumentationBridge.registerIastModule(Mock(HttpResponseHeaderModule))
    final response = new DummyResponse()

    when:
    response.addHeader((String) null, null)

    then:
    noExceptionThrown()
    0 * _
  }

  void 'insecure cookie added using setHeader'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()

    when:
    response.setHeader("Set-Cookie", "user-id=7")

    then:
    1 * module.onHeader('Set-Cookie', 'user-id=7')
    0 * _
  }

  void 'null parameters added using setHeader'() {
    setup:
    InstrumentationBridge.registerIastModule(Mock(HttpResponseHeaderModule))
    final response = new DummyResponse()

    when:
    response.setHeader((String) null, null)

    then:
    noExceptionThrown()
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
    final url = 'http://dummy.url.com'
    def result, expected

    when:
    result = response.encodeRedirectURL(url)

    then:
    1 * module.taintStringIfTainted(_, url) >> { args -> expected = args[0] }
    0 * _
    result == expected
  }

  void 'taint encoded url using encodeURL'() {
    setup:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final response = new DummyResponse()
    final url = 'http://dummy.url.com'
    def result, expected

    when:
    result = response.encodeURL(url)

    then:
    1 * module.taintStringIfTainted(_, url) >> { args -> expected = args[0] }
    0 * _
    expected == result
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
