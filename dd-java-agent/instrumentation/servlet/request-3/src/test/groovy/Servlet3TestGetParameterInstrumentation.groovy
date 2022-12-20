import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.Servlet3TestSuite

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

class Servlet3TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getParameter'() {

    setup:
    WebModule iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    HashMap<String, String[]> map = new HashMap<>()
    map.put("param1", ["value1", "value2"] as String[])
    final servletRequest = Mock(HttpServletRequest)
    servletRequest.getParameterMap() >> map
    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final testSuite = new Servlet3TestSuite(wrapper)


    when:
    Map<String, String[]> returnedMap = testSuite.getParameterMap()

    then:
    returnedMap != null
    returnedMap.get("param1")[0] == "value1"
    returnedMap.get("param1")[1] == "value2"
    2 * iastModule.onParameterValue(_,_)
    1 * iastModule.onParameterName(_)
  }
}
