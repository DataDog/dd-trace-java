import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.Cookie
import org.json.JSONObject
import org.json.JSONTokener

class JSONCookieInstrumentationTest extends AgentTestRunner {

  @Override void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test JSon Cookie toJSONObject'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final cookie = "username = Datadog; expires = Thu, 15 Jun 2020 12:00:00 UTC; path = /"

    when:
    Cookie.toJSONObject(cookie)


    then:
    1 * module.taintObjectIfTainted(_ as JSONObject, cookie)
    1 * module.taintObjectIfTainted(_ as JSONTokener, cookie)
    0 * _
  }
}
