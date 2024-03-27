package datadog.trace.instrumentation.gson

import com.datadog.iast.test.IastAgentTestRunner
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule

class JsonReaderInstrumentationTest extends IastAgentTestRunner {

  void 'test'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final gson = new Gson()

    when:
    final reader = computeUnderIastTrace { new JsonReader(new StringReader(json)) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _ as JsonReader, _ as StringReader)

    when:
    runUnderIastTrace { gson.fromJson(reader, clazz) }

    then:
    calls * module.taintIfTainted(_ as IastContext, _ as String, _ as JsonReader)
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
