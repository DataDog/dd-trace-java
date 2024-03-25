import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONTokener

class JSONTokenerInstrumentationTest extends IastAgentTestRunner {

  void 'test JSONTokener string constructor'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final json = '{"name": "nameTest", "value" : "valueTest"}'

    when:
    runUnderIastTrace { new JSONTokener(json) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _ as JSONTokener, json)
    0 * _
  }
}
