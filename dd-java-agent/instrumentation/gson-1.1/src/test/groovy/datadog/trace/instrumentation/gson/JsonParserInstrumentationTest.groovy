package datadog.trace.instrumentation.gson

import com.google.gson.Gson
import com.google.gson.JsonParser
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule

class JsonParserInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'Test Gson instrumented'(){
    given:
    final gson = new Gson()

    when:
    final result = gson.fromJson('{"name": "nameTest", "value" : "valueTest"}', TestBean)

    then:
    result instanceof TestBean
    result.getName() == 'nameTest'
    result.getValue() == 'valueTest'
  }


  void 'test'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final input = inputClass.newInstance(inputClass == StringReader ? json : json.getBytes())

    when:
    final parser = new JsonParser(input)

    then:
    1 * module.taintIfInputIsTainted(_ as JsonParser, input)
    0 * _

    when:
    parser.ReInit(input)

    then:
    1 * module.taintIfInputIsTainted(_ as JsonParser, input)
    0 * _

    when:
    parser.parse()

    then:
    callsAfterParse * module.taintIfInputIsTainted(_ as String, _ as JsonParser)
    0 * _

    where:
    json | inputClass | callsAfterParse
    '"Test"' | StringReader  | 1
    '1'| StringReader| 0
    '{"name": "nameTest", "value" : "valueTest"}'| StringReader| 4
    '[{"name": "nameTest", "value" : "valueTest"}]'| StringReader| 4
    '[{"name": "nameTest", "value" : "valueTest"}, {"name": "nameTest2", "value" : "valueTest2"}]'| StringReader | 8
    '"Test"' | StringReader|  1
    '1'| ByteArrayInputStream |  0
    '{"name": "nameTest", "value" : "valueTest"}'| ByteArrayInputStream | 4
    '[{"name": "nameTest", "value" : "valueTest"}]'| ByteArrayInputStream |  4
    '[{"name": "nameTest", "value" : "valueTest"}, {"name": "nameTest2", "value" : "valueTest2"}]'| ByteArrayInputStream | 8
  }


  static final class TestBean {

    private String name

    private String value

    String getName() {
      return name
    }

    String getValue() {
      return value
    }
  }
}
