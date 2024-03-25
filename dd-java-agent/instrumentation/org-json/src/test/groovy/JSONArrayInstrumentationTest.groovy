import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class JSONArrayInstrumentationTest extends IastAgentTestRunner {

  void 'test JSONObject returning an array'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final json = """{"menu": {
      "name": "nameTest",
      "value": "File",
      "popup": "Popup",
      "labels": [
          "File",
          "Edit"
        ]
    }}"""

    when:
    final name = computeUnderIastTrace {
      final jsonObject = new JSONObject(json)
      return jsonObject.getJSONObject("menu").getJSONArray("labels").get(0)
    }

    then:
    name == "File"
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, json)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONTokener)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONObject)
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, json)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONArray, _ as JSONObject)
    2 * module.taintIfTainted(_ as IastContext, "File", _ as JSONArray)
    0 * _
  }

  void 'test JSONObject returning an array and calling opt on it'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final json = """{"menu": {
      "name": "nameTest",
      "value": "File",
      "popup": "Popup",
      "labels": [
          "File",
          "Edit"
        ]
    }}"""

    when:
    final name = computeUnderIastTrace {
      final jsonObject = new JSONObject(json)
      return jsonObject.getJSONObject("menu").getJSONArray("labels").optString(0, "defaultvalue")
    }

    then:
    name == "File"
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, json)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONTokener)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONObject)
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, json)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONArray, _ as JSONObject)
    1 * module.taintIfTainted(_ as IastContext, "File", _ as JSONArray)
    0 * _
  }
}
