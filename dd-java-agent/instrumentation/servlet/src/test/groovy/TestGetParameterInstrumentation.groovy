import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.smoketest.controller.TestSuite
import datadog.trace.api.iast.source.WebModule

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

class TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getParameter'() {

    setup:
    WebModule iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final List arrayList = new ArrayList()
    arrayList.add("A")
    arrayList.add("B")
    final servletRequest = Mock(HttpServletRequest)
    servletRequest.getParameter("param") >> "value"
    servletRequest.getParameterValues("param1") >> ["value1", "value2"]
    servletRequest.getParameterNames() >> {return Collections.enumeration(arrayList)}
    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final TestSuite testSuite = new TestSuite(wrapper)


    when:
    testSuite.getParameter("param")
    String[] resultValues = testSuite.getParameterValues("param1")
    List<String> paramNames = testSuite.getParameterNames().toList()

    then:
    resultValues.size() == 2
    paramNames.size() == 2
    1 * iastModule.onParameterValue("param","value")
    1 * iastModule.onParameterValue("param1", "value1")
    1 * iastModule.onParameterValue("param1", "value2")
    1 * iastModule.onParameterName("A")
    1 * iastModule.onParameterName("B")
  }
}
