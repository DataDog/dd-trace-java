import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.codehaus.jackson.JsonFactory

class Json1ParserInstrumentationTest extends AgentTestRunner {

  private final static String JSON_STRING = '{"key1":"value1","key2":"value2","key3":"value3"}'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getCurrentName()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    when:
    jsonParser.getCurrentName()

    then:
    1 * propagationModule.taintIfInputIsTainted(_, jsonParser)
    0 * _


    where:
    jsonParser << [
      new JsonFactory().createJsonParser(JSON_STRING),
      new JsonFactory().createJsonParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  void 'test getText()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    when:
    jsonParser.nextToken()
    final result = jsonParser.getText()

    then:
    result == '{'
    1 * propagationModule.taintIfInputIsTainted('{', jsonParser)

    where:
    jsonParser << [
      new JsonFactory().createJsonParser(JSON_STRING),
      new JsonFactory().createJsonParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  void 'test nextTextValue()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    when:
    jsonParser.nextTextValue()

    then:
    1 * propagationModule.taintIfInputIsTainted(null, jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createJsonParser(JSON_STRING),
      new JsonFactory().createJsonParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }
}
