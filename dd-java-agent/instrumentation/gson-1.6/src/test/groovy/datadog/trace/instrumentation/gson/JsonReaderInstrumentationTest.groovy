package datadog.trace.instrumentation.gson

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule

class JsonReaderInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final gson = new Gson()

    when:
    final reader = new JsonReader(new StringReader(json))

    then:
    1 * module.taintObjectIfTainted(_ as JsonReader, _ as StringReader)

    when:
    gson.fromJson(reader, clazz)

    then:
    calls * module.taintStringIfTainted(_ as String, _ as JsonReader)
    0 * _

    where:
    json | clazz | calls
    '"Test"' | String | 1
    '{"name": "nameTest", "value" : "valueTest"}' | TestBean | 4
    '[{"name": "nameTest", "value" : "valueTest"}]' | TestBean[] | 4
    '[{"name": "nameTest", "value" : "valueTest"}, {"name": "nameTest2", "value" : "valueTest2"}]' | TestBean[].class | 8
  }


  static final class TestBean {

    @SuppressWarnings('CodeNarc')
    private String name

    @SuppressWarnings('CodeNarc')
    private String value
  }
}
