import datadog.smoketest.controller.JavaxHttpServletRequestTestSuite
import datadog.smoketest.controller.JavaxHttpServletRequestWrapperTestSuite
import datadog.smoketest.controller.JavaxServletRequestTestSuite
import datadog.smoketest.controller.JavaxServletRequestWrapperTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import groovy.transform.CompileDynamic

import javax.servlet.ServletInputStream

@CompileDynamic
class ServletRequestCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getInputStream'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final is = Mock(ServletInputStream)
    final servletRequest = Mock(clazz) {
      getInputStream() >> is
    }
    testSuite.init(servletRequest)

    when:
    final result = testSuite.getInputStream()

    then:
    result == is
    1 * iastModule.taintObject(SourceTypes.REQUEST_BODY, is)

    where:
    testSuite                                     | clazz
    new JavaxServletRequestTestSuite()            | javax.servlet.ServletRequest
    new JavaxHttpServletRequestTestSuite()        | javax.servlet.http.HttpServletRequest
    new JavaxServletRequestWrapperTestSuite()     | javax.servlet.ServletRequestWrapper
    new JavaxHttpServletRequestWrapperTestSuite() | javax.servlet.http.HttpServletRequestWrapper
  }

  void 'test getReader'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final reader = Mock(BufferedReader)
    final servletRequest = Mock(clazz) {
      getReader() >> reader
    }

    testSuite.init(servletRequest)

    when:
    final result = testSuite.getReader()

    then:
    result == reader
    1 * iastModule.taintObject(SourceTypes.REQUEST_BODY, reader)

    where:
    testSuite                                     | clazz
    new JavaxServletRequestTestSuite()            | javax.servlet.ServletRequest
    new JavaxHttpServletRequestTestSuite()        | javax.servlet.http.HttpServletRequest
    new JavaxServletRequestWrapperTestSuite()     | javax.servlet.ServletRequestWrapper
    new JavaxHttpServletRequestWrapperTestSuite() | javax.servlet.http.HttpServletRequestWrapper
  }

  void 'test getRequestDispatcher'() {
    setup:
    final iastModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final servletRequest = Mock(clazz)
    final path = 'http://dummy.location.com'

    testSuite.init(servletRequest)

    when:
    testSuite.getRequestDispatcher(path)

    then:
    1 * servletRequest.getRequestDispatcher(path)
    1 * iastModule.onRedirect(path)

    where:
    testSuite                                     | clazz
    new JavaxServletRequestTestSuite()            | javax.servlet.ServletRequest
    new JavaxHttpServletRequestTestSuite()        | javax.servlet.http.HttpServletRequest
    new JavaxServletRequestWrapperTestSuite()     | javax.servlet.ServletRequestWrapper
    new JavaxHttpServletRequestWrapperTestSuite() | javax.servlet.http.HttpServletRequestWrapper
  }
}
