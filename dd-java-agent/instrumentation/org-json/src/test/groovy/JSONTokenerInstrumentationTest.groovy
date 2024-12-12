import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONTokener

class JSONTokenerInstrumentationTest extends AgentTestRunner {

  @Override void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test JSONTokener string constructor'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final json = '{"name": "nameTest", "value" : "valueTest"}'

    when:
    new JSONTokener(json)

    then:
    1 * module.taintObjectIfTainted(_ as JSONTokener, json)
    0 * _
  }
}
