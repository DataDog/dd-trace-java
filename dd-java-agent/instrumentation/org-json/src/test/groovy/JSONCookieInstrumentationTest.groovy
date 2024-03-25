import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.Cookie
import org.json.JSONObject
import org.json.JSONTokener

class JSONCookieInstrumentationTest extends IastAgentTestRunner {

  void 'test JSon Cookie toJSONObject'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final cookie = "username = Datadog; expires = Thu, 15 Jun 2020 12:00:00 UTC; path = /"

    when:
    runUnderIastTrace { Cookie.toJSONObject(cookie) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, cookie)
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, cookie)
    0 * _
  }
}
