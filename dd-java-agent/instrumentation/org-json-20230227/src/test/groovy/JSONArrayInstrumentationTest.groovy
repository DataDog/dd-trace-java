import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONArray
import org.json.JSONObject

class JSONArrayInstrumentationTest extends InstrumentationSpecification {

  private static json = """{"menu": {
    "name": "nameTest",
    "value": "File",
    "popup": "Popup",
    "labels": [
        "File",
        "Edit"
      ]
  }}"""

  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test JSONObject returning an array'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final jsonObject = new JSONObject(json)
    final menuObject = jsonObject.getJSONObject("menu")

    when:
    final array = menuObject.getJSONArray("labels")

    then:
    array.length() == 2
    array.get(0) == "File"
    array.get(1) == "Edit"
    1 * module.taintObjectIfTainted(_ as JSONArray, _ as JSONObject)
    0 * _
  }

  void 'test JSONObject returning an array and calling opt on it'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final jsonObject = new JSONObject(json)
    final jsonArray =jsonObject.getJSONObject("menu").getJSONArray("labels")

    when:
    final name = jsonArray.optString(0, "defaultvalue")

    then:
    name == "File"
    1 * module.taintStringIfTainted("File", _ as JSONArray)
    0 * _

    where:
    method      | arguments
    "opt"       | [0]
    "optString" | [0, "defaultvalue"]
    "get"       | [0]
    "getString" | [0, "defaultvalue"]
  }
}
