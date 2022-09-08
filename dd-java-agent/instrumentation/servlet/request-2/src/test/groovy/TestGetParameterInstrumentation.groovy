import foo.bar.test.TestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.instrumentation.servlet2.callsite.MockTainter

import javax.servlet.ServletRequestWrapper
import javax.servlet.ServletRequest

class TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getParameter'() {

    setup:

    final List arrayList = new ArrayList()
    arrayList.add("A")
    arrayList.add("B")
    final servletRequest = Mock(ServletRequest)
    servletRequest.getParameter("pepe") >> "manolo"
    servletRequest.getParameterValues("pepe") >> ["manolo", "juan"]
    servletRequest.getParameterNames() >> {return Collections.enumeration(arrayList)}
    final ServletRequestWrapper wrapper = new ServletRequestWrapper(servletRequest)
    final TestSuite testSuite = new TestSuite(wrapper)


    when:
    String result = testSuite.getParameter("pepe")
    String[] resultValues = testSuite.getParameterValues("pepe")
    List<String> paramNames = testSuite.getParameterNames().toList()

    then:
    MockTainter.isTainted(result)
    resultValues.size() == 2
    for (String value:resultValues){
      MockTainter.isTainted(value)
    }

    paramNames.size() == 2
    for (String value:paramNames){
      MockTainter.isTainted(value)
    }
  }
}
