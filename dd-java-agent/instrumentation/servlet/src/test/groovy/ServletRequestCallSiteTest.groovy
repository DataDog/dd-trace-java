import datadog.smoketest.controller.ServletRequestTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule

import javax.servlet.ServletInputStream
import javax.servlet.ServletRequest
import javax.servlet.ServletRequestWrapper
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

class ServletRequestCallSiteTest extends  AgentTestRunner{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getInputStream'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final is = Mock(ServletInputStream)
    final testSuite = new ServletRequestTestSuite(Mock(clazz) {
      getInputStream() >> is
    })

    when:
    final result = testSuite.getInputStream()

    then:
    1 * iastModule.onGetInputStream(is)

    where:
    clazz                     | _
    ServletRequest            | _
    ServletRequestWrapper     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test getReader'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final reader = Mock(BufferedReader)
    final testSuite = new ServletRequestTestSuite(Mock(clazz) {
      getReader() >> reader
    })

    when:
    final result = testSuite.getReader()

    then:
    1 * iastModule.onGetReader(reader)

    where:
    clazz                     | _
    ServletRequest            | _
    ServletRequestWrapper     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }
}
