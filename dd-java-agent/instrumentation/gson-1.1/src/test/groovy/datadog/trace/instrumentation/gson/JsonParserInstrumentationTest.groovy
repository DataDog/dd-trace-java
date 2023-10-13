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

    when:
    final parser = new JsonParser(new StringReader(json))

    then:
    1 * module.taintIfInputIsTainted(_ as JsonParser, _ as StringReader)
    0 * _

    when:
    parser.parse()

    then:
    callsAfterParse * module.taintIfInputIsTainted(_ as String, _ as JsonParser)
    0 * _

    where:
    json | callsAfterParse
    '"Test"' | 1
    '1' | 0
    '{"name": "nameTest", "value" : "valueTest"}' | 4
    '[{"name": "nameTest", "value" : "valueTest"}]' | 4
    '[{"name": "nameTest", "value" : "valueTest"}, {"name": "nameTest2", "value" : "valueTest2"}]' | 8
  }


  static final class TestBean {

    private String name

    private String value

    String getName() {
      return name
    }

    void setName(String name) {
      this.name = name
    }

    String getValue() {
      return value
    }

    void setValue(String value) {
      this.value = value
    }
  }
}
