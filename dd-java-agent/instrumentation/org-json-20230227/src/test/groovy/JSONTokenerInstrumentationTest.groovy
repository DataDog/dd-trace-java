import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.json.JSONTokener

class JSONTokenerInstrumentationTest extends InstrumentationSpecification {

  private static final String JSON_STRING = '{"name": "nameTest", "value" : "valueTest"}' // Reused JSON String

  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test JSONTokener constructor with different argument types'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new JSONTokener(args)

    then:
    1 * module.taintObjectIfTainted(_ as JSONTokener, _)
    if(args instanceof String) {
      1 * module.taintObjectIfTainted(_ as Reader, _ as String)
    } else if (args instanceof InputStream) {
      1 * module.taintObjectIfTainted(_ as Reader, _ as InputStream)
    }
    0 * _

    where:
    args << [
      JSON_STRING,
      // String input
      new ByteArrayInputStream(JSON_STRING.bytes),
      // InputStream input
      new StringReader(JSON_STRING) // Reader input
    ]
  }
}
