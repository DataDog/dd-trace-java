import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONObject
import org.json.JSONTokener
import spock.lang.Shared

class JSONObjectInstrumentationTest extends InstrumentationSpecification {

  @Override void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  @Shared
  Map<?, ?> tainteds = new IdentityHashMap<>()


  void setup() {
    tainteds.clear()
  }


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
    new JSONObject(json)

    then:
    1 * module.taintObjectIfTainted(_ as Reader, json)
    1 * module.taintObjectIfTainted(_ as JSONTokener, _ as Reader)
    // Two calls are necessary, once for the whole object and other for the menu object
    2 * module.taintObjectIfTainted(_ as JSONObject, _ as JSONTokener)
    0 * _
  }

  void 'test JSONObject JSonTokenizer constructor'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final json = '{"name": "nameTest", "value" : "valueTest"}'
    final jsonTokener = new JSONTokener(json)

    when:
    new JSONObject(jsonTokener)

    then:
    1 * module.taintObjectIfTainted(_ as JSONObject, _ as JSONTokener)
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
    new JSONObject(map)

    then:
    1 * module.taintObjectIfTainted(_ as JSONObject, map)
    0 * _
  }

  void 'test JSONObject #method'() {
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
    final jsonObject = new JSONObject(json)
    final getObject =jsonObject.getJSONObject("menu")

    when:
    final name = getObject."$method"('name')

    then:
    name == "nameTest"
    1 * module.taintStringIfTainted("nameTest", _ as JSONObject)
    0 * _

    where:
    method << ['get', 'getString', 'opt', 'optString']
  }

  void 'test JSONObject elements are  tainted'() {
    given:
    final module = Mock(PropagationModule) {
      taintObjectIfTainted(_, _) >> {
        if (tainteds.containsKey(it[1])) {
          tainteds.put(it[0], null)
        }
      }
      taintStringIfTainted(_, _) >> {
        if (tainteds.containsKey(it[1])) {
          tainteds.put(it[0], null)
        }
      }
    }
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
    tainteds.put(json, null)
    final jsonObject = new JSONObject(json)
    final getObject =jsonObject.getJSONObject("menu")

    when:
    final name = getObject.get('name')
    final file = getObject.get('labels').get(0)
    final edit = getObject.get('labels').get(1)

    then:
    name == "nameTest"
    tainteds.containsKey(name)
    file == "File"
    tainteds.containsKey(file)
    edit == "Edit"
    tainteds.containsKey(edit)
  }
}
