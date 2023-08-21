import datadog.smoketest.controller.ServletRequestTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic

import javax.servlet.ServletInputStream
import javax.servlet.ServletRequest
import javax.servlet.ServletRequestWrapper
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

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
    final testSuite = new ServletRequestTestSuite(Mock(clazz) {
      getInputStream() >> is
    })

    when:
    final result = testSuite.getInputStream()

    then:
    result == is
    1 * iastModule.taint(SourceTypes.REQUEST_BODY, is)

    where:
    clazz                     | _
    ServletRequest            | _
    ServletRequestWrapper     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getReader'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final reader = Mock(BufferedReader)
    final testSuite = new ServletRequestTestSuite(Mock(clazz) {
      getReader() >> reader
    })

    when:
    final result = testSuite.getReader()

    then:
    result == reader
    1 * iastModule.taint(SourceTypes.REQUEST_BODY, reader)

    where:
    clazz                     | _
    ServletRequest            | _
    ServletRequestWrapper     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }
}
