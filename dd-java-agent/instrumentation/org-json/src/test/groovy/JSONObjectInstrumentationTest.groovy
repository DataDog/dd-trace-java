import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONObject
import org.json.JSONTokener

class JSONObjectInstrumentationTest extends IastAgentTestRunner {

  void 'test JSONObject string constructor'() {
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
      return jsonObject.getJSONObject("menu").get("name")
    }

    then:
    name == "nameTest"
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, json)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONTokener)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONObject)
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, json)
    2 * module.taintIfTainted(_ as IastContext, "nameTest", _ as JSONObject)
    0 * _
  }

  void 'test JSONObject opt'() {
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
      return jsonObject.getJSONObject("menu").optString("name")
    }

    then:
    name == "nameTest"
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, json)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONTokener)
    2 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONObject)
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, json)
    1 * module.taintIfTainted(_ as IastContext, "nameTest", _ as JSONObject)
    0 * _
  }



  void 'test JSONObject JSonTokenizer constructor'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final json = '{"name": "nameTest", "value" : "valueTest"}'

    when:
    final name = computeUnderIastTrace {
      final jsonObject = new JSONObject(new JSONTokener(json))
      return jsonObject.get("name")
    }

    then:
    name == "nameTest"
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, _ as JSONTokener)
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, json)
    2 * module.taintIfTainted(_ as IastContext, "nameTest", _ as JSONObject)
    0 * _
  }

  void 'test JSONObject map constructor'(){
    given:
    final Map<String, String> map = new HashMap<>()
    map.put("name", "nameTest")
    map.put("age", "22")
    map.put("city", "chicago")
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace {
      final jsonObject = new JSONObject(map)
      jsonObject.get("name")
    }

    then:
    1 * module.taintIfTainted(_ as IastContext, _ as JSONObject, map)
    2 * module.taintIfTainted(_ as IastContext, "nameTest", _ as JSONObject)
    0 * _
  }
}
