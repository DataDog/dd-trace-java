import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.JakartaTestSuite
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper

class JakartaCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getParameter map'() {

    setup:
    WebModule iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    HashMap<String, String[]> map = new HashMap<>()
    map.put("param1", ["value1", "value2"] as String[])
    final servletRequest = Mock(HttpServletRequest)
    servletRequest.getParameterMap() >> map
    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final testSuite = new JakartaTestSuite(wrapper)


    when:
    Map<String, String[]> returnedMap = testSuite.getParameterMap()

    then:
    returnedMap != null
    returnedMap.get("param1")[0] == "value1"
    returnedMap.get("param1")[1] == "value2"
    2 * iastModule.onParameterValue(_,_)
    1 * iastModule.onParameterName(_)
  }

  def 'test getParameterValues and getParameterNames'() {

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
    final JakartaTestSuite testSuite = new JakartaTestSuite(wrapper)


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
