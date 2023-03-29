import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.source.WebModule
import groovy.transform.CompileDynamic

import javax.servlet.ServletInputStream
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest

@CompileDynamic
class HttpServletRequestIastInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  def cleanup() {
    final span = TEST_TRACER.activeSpan()
    if (span != null) {
      span.finish()
    }
    InstrumentationBridge.clearIastModules()
  }

  void startRequest(final Object iastRequestContext) {
    def span = TEST_TRACER.buildSpan("test-request").withRequestContextData(RequestContextSlot.IAST, iastRequestContext).start()
    TEST_TRACER.activateNext(span)
  }

  def 'test getQueryString -> #value and request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getQueryString() >> value
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getQueryString()

    then:
    result == value
    1 * webModule.onQueryString(value)
    0 * _

    where:
    value   | hasRequestContext
    'value' | false
    null    | false
    'value' | true
    null    | true
  }

  def 'test getParameter(#name) -> #value and request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameter(name) >> value
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getParameter(name)

    then:
    result == value
    1 * webModule.onParameterValue(name, value)
    0 * _

    where:
    name    | value   | hasRequestContext
    'param' | 'value' | false
    'param' | null    | false
    null    | 'value' | false
    null    | null    | false
    'param' | 'value' | true
    'param' | null    | true
    null    | 'value' | true
    null    | null    | true
  }

  def 'test getParameter(param) throws and request context = #hasRequestContext'() {
    given:
    def name = "param"
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameter(name) >> { throw e }
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    req.getParameter(name)

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getParameterNames() -> #list with request context = #hasRequestContext'() {
    given:
    def enumeration = Collections.enumeration(list)
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameterNames() >> enumeration
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getParameterNames()

    then:
    0 * _

    when:
    final resultList = result.toList()

    then:
    resultList == list
    if (hasRequestContext) {
      for (def elem : list) {
        1 * webModule.onParameterName(elem, iastRequestContext)
      }
    }
    0 * _

    where:
    list           | hasRequestContext
    []             | false
    ["foo", "bar"] | false
    []             | true
    ["foo", "bar"] | true
  }

  def 'test getParameterNames() -> null with request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameterNames() >> null
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getParameterNames()

    then:
    result == null
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getParameterNames() throws with request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameterNames() >> { throw e }
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    req.getParameterNames()

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getParameterNames() with throwing enumerable'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    def throwingEnumerable = new Enumeration() {
        @Override
        boolean hasMoreElements() {
          return true
        }

        @Override
        Object nextElement() {
          throw e
        }
      }
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameterNames() >> throwingEnumerable
    })

    when:
    final iastRequestContext = new Object()
    startRequest(iastRequestContext)

    then:
    _ * _

    when:
    def result = req.getParameterNames()

    then:
    result != null
    0 * _

    when:
    result.toList()

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _
  }

  def 'test getParameterValues(#name) -> #values and request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final valuesArray = values == null? null : values.toArray(new String[0])
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameterValues(name) >> valuesArray
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getParameterValues(name)

    then:
    result == valuesArray
    1 * webModule.onParameterValues(name, valuesArray)
    0 * _

    where:
    name    | values         | hasRequestContext
    'param' | null           | false
    'param' | []             | false
    'param' | ['foo', 'bar'] | false
    null    | null           | false
    'param' | null           | true
    'param' | []             | true
    'param' | ['foo', 'bar'] | true
    null    | null           | true
  }


  def 'test getHeader(#name) -> #value and request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeader(name) >> value
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getHeader(name)

    then:
    result == value
    1 * webModule.onHeaderValue(name, value)
    0 * _

    where:
    name    | value   | hasRequestContext
    'param' | 'value' | false
    'param' | null    | false
    null    | 'value' | false
    null    | null    | false
    'param' | 'value' | true
    'param' | null    | true
    null    | 'value' | true
    null    | null    | true
  }


  def 'test getHeader(param) throws and request context = #hasRequestContext'() {
    given:
    def name = "param"
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeader(name) >> { throw e }
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    req.getHeader(name)

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getHeaders(name) -> #list with request context = #hasRequestContext'() {
    given:
    final name = "name"
    def enumeration = Collections.enumeration(list)
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaders(name) >> enumeration
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getHeaders(name)

    then:
    0 * _

    when:
    final resultList = result.toList()

    then:
    resultList == list
    if (hasRequestContext) {
      for (def elem : list) {
        1 * webModule.onHeaderValue(name, elem, iastRequestContext)
      }
    }
    0 * _

    where:
    list           | hasRequestContext
    []             | false
    ["foo", "bar"] | false
    []             | true
    ["foo", "bar"] | true
  }

  def 'test getHeaders(name) -> null with request context = #hasRequestContext'() {
    given:
    final name = "name"
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaders(name) >> null
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getHeaders(name)

    then:
    result == null
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getHeaders(name) throws with request context = #hasRequestContext'() {
    given:
    final name = "name"
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaders(name) >> { throw e }
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    req.getHeaders(name)

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getHeaders(name) with throwing enumerable'() {
    given:
    final name = "name"
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    def throwingEnumerable = new Enumeration() {
        @Override
        boolean hasMoreElements() {
          return true
        }

        @Override
        Object nextElement() {
          throw e
        }
      }
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaders(name) >> throwingEnumerable
    })

    when:
    final iastRequestContext = new Object()
    startRequest(iastRequestContext)

    then:
    _ * _

    when:
    def result = req.getHeaders(name)

    then:
    result != null
    0 * _

    when:
    result.toList()

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _
  }


  def 'test getHeaderNames() -> #list with request context = #hasRequestContext'() {
    given:
    def enumeration = Collections.enumeration(list)
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaderNames() >> enumeration
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getHeaderNames()

    then:
    0 * _

    when:
    final resultList = result.toList()

    then:
    resultList == list
    if (hasRequestContext) {
      for (def elem : list) {
        1 * webModule.onHeaderName(elem, iastRequestContext)
      }
    }
    0 * _

    where:
    list           | hasRequestContext
    []             | false
    ["foo", "bar"] | false
    []             | true
    ["foo", "bar"] | true
  }

  def 'test getHeaderNames() -> null with request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaderNames() >> null
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getHeaderNames()

    then:
    result == null
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getHeaderNames() throws with request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaderNames() >> { throw e }
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    req.getHeaderNames()

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _

    where:
    hasRequestContext | _
    false             | _
    true              | _
  }

  def 'test getHeaderNames() with throwing enumerable'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    def e = new RuntimeException("THROWN")
    def throwingEnumerable = new Enumeration() {
        @Override
        boolean hasMoreElements() {
          return true
        }

        @Override
        Object nextElement() {
          throw e
        }
      }
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getHeaderNames() >> throwingEnumerable
    })

    when:
    final iastRequestContext = new Object()
    startRequest(iastRequestContext)

    then:
    _ * _

    when:
    def result = req.getHeaderNames()

    then:
    result != null
    0 * _

    when:
    result.toList()

    then:
    def thrownException = thrown(RuntimeException)
    thrownException.is(e)
    0 * _
  }

  def 'test getCookies() -> #list with request context = #hasRequestContext'() {
    given:
    def cookies = list.collect {new Cookie("mycookie", it) }.toArray() as Cookie[]
    def module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getCookies() >> cookies
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getCookies()

    then:
    result.is(cookies)
    1 *  module.taint(SourceTypes.REQUEST_COOKIE_VALUE, (Object[]) cookies)
    0 * _

    where:
    list           | hasRequestContext
    []             | false
    ["foo", "bar"] | false
    []             | true
    ["foo", "bar"] | true
  }

  def 'test getInputStream() and request context = #hasRequestContext'() {
    given:
    def module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getInputStream() >> stream
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getInputStream()

    then:
    result == stream
    1 * module.taint(SourceTypes.REQUEST_BODY, stream)
    0 * _

    where:
    stream                   | hasRequestContext
    null                     | false
    Stub(ServletInputStream) | false
    null                     | true
    Stub(ServletInputStream) | true
  }


  def 'test getReader() and request context = #hasRequestContext'() {
    given:
    def module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getReader() >> stream
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getReader()

    then:
    result == stream
    1 * module.taint(SourceTypes.REQUEST_BODY, stream)
    0 * _

    where:
    stream               | hasRequestContext
    null                 | false
    Stub(BufferedReader) | false
    null                 | true
    Stub(BufferedReader) | true
  }
}
