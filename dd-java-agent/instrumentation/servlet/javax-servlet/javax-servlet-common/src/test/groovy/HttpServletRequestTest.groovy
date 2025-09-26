import datadog.smoketest.controller.JavaxHttpServletRequestTestSuite
import datadog.smoketest.controller.JavaxHttpServletRequestWrapperTestSuite
import datadog.smoketest.controller.ServletRequestTestSuite
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext

import javax.servlet.RequestDispatcher
import javax.servlet.ServletInputStream
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

import datadog.trace.agent.tooling.iast.TaintableEnumeration

class HttpServletRequestTest extends InstrumentationSpecification {

  private Object iastCtx

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void setup() {
    iastCtx = Stub(IastContext)
  }


  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test getHeader #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getHeader('header') }

    then:
    result == 'value'
    1 * mock.getHeader('header') >> 'value'
    1 * iastModule.taintString(iastCtx, 'value', SourceTypes.REQUEST_HEADER_VALUE, 'header')
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getHeaders #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final headers = ['value1', 'value2']
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getHeaders('headers').collect() }

    then:
    result == headers
    1 * mock.getHeaders('headers') >> Collections.enumeration(headers)
    headers.each { 1 * iastModule.taintString(iastCtx, it, SourceTypes.REQUEST_HEADER_VALUE, 'headers') }
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getHeaderNames #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final headers = ['header1', 'header2']
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getHeaderNames().collect() }

    then:
    result == headers
    1 * mock.getHeaderNames() >> Collections.enumeration(headers)
    headers.each { 1 * iastModule.taintString(iastCtx, it, SourceTypes.REQUEST_HEADER_NAME, it) }
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getParameter #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getParameter('parameter') }

    then:
    result == 'value'
    1 * mock.getParameter('parameter') >> 'value'
    1 * iastModule.taintString(iastCtx, 'value', SourceTypes.REQUEST_PARAMETER_VALUE, 'parameter')
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getParameterValues #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final values = ['value1', 'value2']
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getParameterValues('parameter').collect() }

    then:
    result == values
    1 * mock.getParameterValues('parameter') >> { values as String[] }
    values.each { 1 * iastModule.taintString(iastCtx, it, SourceTypes.REQUEST_PARAMETER_VALUE, 'parameter') }
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getParameterMap #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final parameters = [parameter: ['header1', 'header2'] as String[]]
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getParameterMap() }

    then:
    result == parameters
    1 * mock.getParameterMap() >> parameters
    parameters.each { key, values ->
      1 * iastModule.taintString(iastCtx, key, SourceTypes.REQUEST_PARAMETER_NAME, key)
      values.each { value ->
        1 * iastModule.taintString(iastCtx, value, SourceTypes.REQUEST_PARAMETER_VALUE, key)
      }
    }
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getParameterNames #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final parameters = ['param1', 'param2']
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getParameterNames().collect() }

    then:
    result == parameters
    1 * mock.getParameterNames() >> Collections.enumeration(parameters)
    parameters.each { 1 * iastModule.taintString(iastCtx, it, SourceTypes.REQUEST_PARAMETER_NAME, it) }
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getCookies #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookies = [new Cookie('name1', 'value1'), new Cookie('name2', 'value2')] as Cookie[]
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getCookies() }

    then:
    result == cookies
    1 * mock.getCookies() >> cookies
    cookies.each { 1 * iastModule.taintObject(iastCtx, it, SourceTypes.REQUEST_COOKIE_VALUE) }
    0 * _

    where:
    suite << testSuite()
  }

  void 'test that get headers does not fail when servlet related code fails #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final enumeration = Mock(Enumeration) {
      hasMoreElements() >> { throw new NuclearBomb('Boom!!!') }
    }
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final headers = runUnderIastTrace { request.getHeaders('header') }

    then:
    1 * mock.getHeaders('header') >> enumeration
    noExceptionThrown()

    when:
    headers.hasMoreElements()

    then:
    final bomb = thrown(NuclearBomb)
    bomb.stackTrace.find { it.className == TaintableEnumeration.name } == null

    where:
    suite << testSuite()
  }

  void 'test that get header names does not fail when servlet related code fails #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final enumeration = Mock(Enumeration) {
      hasMoreElements() >> { throw new NuclearBomb('Boom!!!') }
    }
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getHeaderNames() }

    then:
    1 * mock.getHeaderNames() >> enumeration
    noExceptionThrown()

    when:
    result.hasMoreElements()

    then:
    final bomb = thrown(NuclearBomb)
    bomb.stackTrace.find { it.className == TaintableEnumeration.name } == null

    where:
    suite << testSuite()
  }

  void 'test get query string #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final queryString = 'paramName=paramValue'
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final String result = runUnderIastTrace { request.getQueryString() }

    then:
    result == queryString
    1 * mock.getQueryString() >> queryString
    1 * iastModule.taintString(iastCtx, queryString, SourceTypes.REQUEST_QUERY)
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getInputStream #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final is = Mock(ServletInputStream)
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getInputStream() }

    then:
    result == is
    1 * mock.getInputStream() >> is
    1 * iastModule.taintObject(iastCtx, is, SourceTypes.REQUEST_BODY)
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getReader #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final reader = Mock(BufferedReader)
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getReader() }

    then:
    result == reader
    1 * mock.getReader() >> reader
    1 * iastModule.taintObject(iastCtx, reader, SourceTypes.REQUEST_BODY)
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getRequestDispatcher #iterationIndex'() {
    setup:
    final iastModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = 'http://dummy.location.com'
    final dispatcher = Mock(RequestDispatcher)
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getRequestDispatcher(path) }

    then:
    result == dispatcher
    1 * mock.getRequestDispatcher(path) >> dispatcher
    1 * iastModule.onRedirect(path)
    0 * _

    where:
    suite << testSuite()
  }

  void 'test getRequestURI #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final uri = 'retValue'
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getRequestURI() }

    then:
    result == uri
    1 * mock.getRequestURI() >> uri
    1 * iastModule.taintString(iastCtx, uri, SourceTypes.REQUEST_PATH)
    0 * _

    where:
    suite << testSuiteCallSites()
  }

  void 'test getPathInfo #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final pathInfo = 'retValue'
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getPathInfo() }

    then:
    result == pathInfo
    1 * mock.getPathInfo() >> pathInfo
    1 * iastModule.taintString(iastCtx, pathInfo, SourceTypes.REQUEST_PATH)
    0 * _

    where:
    suite << testSuiteCallSites()
  }

  void 'test getPathTranslated #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final pathTranslated = 'retValue'
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getPathTranslated() }

    then:
    result == pathTranslated
    1 * mock.getPathTranslated() >> pathTranslated
    1 * iastModule.taintString(iastCtx, pathTranslated, SourceTypes.REQUEST_PATH)
    0 * _

    where:
    suite << testSuiteCallSites()
  }

  void 'test getRequestURL #iterationIndex'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final url = new StringBuffer('retValue')
    final mock = Mock(HttpServletRequest)
    final request = suite.call(mock)

    when:
    final result = runUnderIastTrace { request.getRequestURL() }

    then:
    result == url
    1 * mock.getRequestURL() >> url
    1 * iastModule.taintObject(iastCtx, url, SourceTypes.REQUEST_URI)
    0 * _

    where:
    suite << testSuiteCallSites()
  }

  protected <E> E runUnderIastTrace(Closure<E> cl) {
    final ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    final span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      return AgentTracer.activateSpan(span).withCloseable(cl)
    } finally {
      span.finish()
    }
  }

  private List<Closure<? extends HttpServletRequest>> testSuite() {
    return [
      { HttpServletRequest request -> new CustomRequest(request: request) },
      { HttpServletRequest request -> new CustomRequestWrapper(new CustomRequest(request: request)) },
      { HttpServletRequest request ->
        new HttpServletRequestWrapper(new CustomRequest(request: request))
      }
    ]
  }

  private List<Closure<? extends ServletRequestTestSuite>> testSuiteCallSites() {
    return [
      { HttpServletRequest request -> new JavaxHttpServletRequestTestSuite(request) },
      { HttpServletRequest request -> new JavaxHttpServletRequestWrapperTestSuite(new CustomRequestWrapper(request)) },
    ]
  }

  private static class NuclearBomb extends RuntimeException {
    NuclearBomb(final String message) {
      super(message)
    }
  }

  private static class CustomRequest implements HttpServletRequest {
    @Delegate
    private HttpServletRequest request
  }

  private static class CustomRequestWrapper extends HttpServletRequestWrapper {

    CustomRequestWrapper(final HttpServletRequest request) {
      super(request)
    }
  }
}
